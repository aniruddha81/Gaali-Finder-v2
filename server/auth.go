package main

import (
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"database/sql"
	"encoding/base64"
	"errors"
	"fmt"
	"html"
	"net/http"
	netmail "net/mail"
	"strings"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"golang.org/x/crypto/argon2"
)

// ---- passwords ----

const (
	argonTime    = 2
	argonMemory  = 19 * 1024 // KiB; OWASP's minimum argon2id profile
	argonThreads = 1
)

func hashPassword(pw string) string {
	salt := randBytes(16)
	h := argon2.IDKey([]byte(pw), salt, argonTime, argonMemory, argonThreads, 32)
	b64 := base64.RawStdEncoding
	return fmt.Sprintf("$argon2id$v=19$m=%d,t=%d,p=%d$%s$%s",
		argonMemory, argonTime, argonThreads, b64.EncodeToString(salt), b64.EncodeToString(h))
}

func checkPassword(pw, encoded string) bool {
	p := strings.Split(encoded, "$") // "", argon2id, v=19, params, salt, hash
	if len(p) != 6 || p[1] != "argon2id" {
		return false
	}
	var m, t uint32
	var th uint8
	if _, err := fmt.Sscanf(p[3], "m=%d,t=%d,p=%d", &m, &t, &th); err != nil {
		return false
	}
	salt, err1 := base64.RawStdEncoding.DecodeString(p[4])
	want, err2 := base64.RawStdEncoding.DecodeString(p[5])
	if err1 != nil || err2 != nil {
		return false
	}
	got := argon2.IDKey([]byte(pw), salt, t, m, th, uint32(len(want)))
	return subtle.ConstantTimeCompare(got, want) == 1
}

// dummyHash is checked when the e-mail is unknown, so login timing does not reveal accounts.
var dummyHash = hashPassword("not-a-real-password")

func hashToken(t string) []byte { h := sha256.Sum256([]byte(t)); return h[:] }

func cleanEmail(s string) (string, error) {
	s = strings.ToLower(strings.TrimSpace(s))
	a, err := netmail.ParseAddress(s)
	if err != nil || a.Address != s || len(s) > 254 {
		return "", fail(http.StatusBadRequest, "invalid_email", "Enter a valid email address.")
	}
	return s, nil
}

func isUnique(err error) bool {
	return err != nil && strings.Contains(err.Error(), "UNIQUE constraint failed")
}

// ---- sessions ----

type userDTO struct {
	ID            string `json:"id"`
	Email         string `json:"email"`
	DisplayName   string `json:"displayName"`
	EmailVerified bool   `json:"emailVerified"`
}

type sessionDTO struct {
	AccessToken  string  `json:"accessToken"`
	RefreshToken string  `json:"refreshToken"`
	ExpiresIn    int     `json:"expiresIn"` // access token lifetime, seconds
	User         userDTO `json:"user"`
}

func loadUser(ctx context.Context, q dbtx, id string) (userDTO, error) {
	var u userDTO
	var verified sql.NullInt64
	err := q.QueryRowContext(ctx,
		`SELECT id, email, display_name, email_verified_at FROM users WHERE id = ?`, id,
	).Scan(&u.ID, &u.Email, &u.DisplayName, &verified)
	if errors.Is(err, sql.ErrNoRows) {
		return u, fail(http.StatusUnauthorized, "unauthorized", "Sign in again.")
	}
	u.EmailVerified = verified.Valid
	return u, err
}

// newSession mints an access JWT and stores a fresh refresh token in the given family.
func (s *server) newSession(ctx context.Context, q dbtx, uid, family string) (sessionDTO, error) {
	now := time.Now()
	access, err := jwt.NewWithClaims(jwt.SigningMethodHS256, jwt.RegisteredClaims{
		Subject:   uid,
		IssuedAt:  jwt.NewNumericDate(now),
		ExpiresAt: jwt.NewNumericDate(now.Add(accessTTL)),
	}).SignedString(s.cfg.jwtSecret)
	if err != nil {
		return sessionDTO{}, err
	}
	refresh := randToken()
	if _, err := q.ExecContext(ctx,
		`INSERT INTO refresh_tokens (id, user_id, token_hash, family_id, expires_at) VALUES (?, ?, ?, ?, ?)`,
		randHex(16), uid, hashToken(refresh), family, now.Add(refreshTTL).UnixMilli()); err != nil {
		return sessionDTO{}, err
	}
	u, err := loadUser(ctx, q, uid)
	return sessionDTO{AccessToken: access, RefreshToken: refresh, ExpiresIn: int(accessTTL.Seconds()), User: u}, err
}

// authed wraps a handler that needs a valid access token; the user id is passed in.
func (s *server) authed(fn func(http.ResponseWriter, *http.Request, string) error) http.HandlerFunc {
	return s.h(func(w http.ResponseWriter, r *http.Request) error {
		tok := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
		var claims jwt.RegisteredClaims
		_, err := jwt.ParseWithClaims(tok, &claims,
			func(*jwt.Token) (any, error) { return s.cfg.jwtSecret, nil },
			jwt.WithValidMethods([]string{"HS256"}), jwt.WithExpirationRequired())
		if err != nil || claims.Subject == "" {
			return fail(http.StatusUnauthorized, "unauthorized", "Sign in again.")
		}
		return fn(w, r, claims.Subject)
	})
}

// ---- e-mail/password ----

func (s *server) register(w http.ResponseWriter, r *http.Request) error {
	var in struct{ Email, Password, DisplayName string }
	if err := readJSON(r, &in); err != nil {
		return err
	}
	email, err := cleanEmail(in.Email)
	if err != nil {
		return err
	}
	if n := len(in.Password); n < 10 || n > 128 {
		return fail(http.StatusBadRequest, "weak_password", "Use a password of 10 to 128 characters.")
	}
	name := displayNameOr(in.DisplayName, email)

	id := randHex(16)
	_, err = s.db.ExecContext(r.Context(),
		`INSERT INTO users (id, email, password_hash, display_name, created_at) VALUES (?, ?, ?, ?, ?)`,
		id, email, hashPassword(in.Password), name, nowMs())
	if isUnique(err) {
		return fail(http.StatusConflict, "email_taken", "That email already has an account. Sign in instead.")
	}
	if err != nil {
		return err
	}
	s.sendEmailToken(r.Context(), id, email, name, "verify")

	sess, err := s.newSession(r.Context(), s.db, id, randHex(16))
	if err != nil {
		return err
	}
	writeJSON(w, http.StatusCreated, sess)
	return nil
}

func displayNameOr(given, email string) string {
	name := strings.TrimSpace(given)
	if name == "" {
		name, _, _ = strings.Cut(email, "@")
	}
	if r := []rune(name); len(r) > 40 {
		name = string(r[:40])
	}
	return name
}

func (s *server) login(w http.ResponseWriter, r *http.Request) error {
	var in struct{ Email, Password string }
	if err := readJSON(r, &in); err != nil {
		return err
	}
	email := strings.ToLower(strings.TrimSpace(in.Email))
	if !s.lim.allow("login-email|"+email, 10, 15*time.Minute) {
		return errTooMany
	}
	var id string
	var hash sql.NullString
	err := s.db.QueryRowContext(r.Context(), `SELECT id, password_hash FROM users WHERE email = ?`, email).Scan(&id, &hash)
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return err
	}
	ok := false
	if err == nil && hash.Valid {
		ok = checkPassword(in.Password, hash.String)
	} else {
		checkPassword(in.Password, dummyHash)
	}
	if !ok {
		return fail(http.StatusUnauthorized, "invalid_credentials", "Wrong email or password.")
	}
	sess, err := s.newSession(r.Context(), s.db, id, randHex(16))
	if err != nil {
		return err
	}
	writeJSON(w, http.StatusOK, sess)
	return nil
}

// refresh rotates the refresh token. Presenting one that was already rotated means it leaked,
// so the whole family is revoked.
func (s *server) refresh(w http.ResponseWriter, r *http.Request) error {
	var in struct {
		RefreshToken string `json:"refreshToken"`
	}
	if err := readJSON(r, &in); err != nil {
		return err
	}
	ctx := r.Context()
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()

	var id, uid, family string
	var expires int64
	var revoked sql.NullInt64
	err = tx.QueryRowContext(ctx,
		`SELECT id, user_id, family_id, expires_at, revoked_at FROM refresh_tokens WHERE token_hash = ?`,
		hashToken(in.RefreshToken)).Scan(&id, &uid, &family, &expires, &revoked)
	bad := fail(http.StatusUnauthorized, "invalid_token", "Sign in again.")
	if errors.Is(err, sql.ErrNoRows) {
		return bad
	}
	if err != nil {
		return err
	}
	now := nowMs()
	if revoked.Valid {
		if _, err := tx.ExecContext(ctx, `UPDATE refresh_tokens SET revoked_at = ? WHERE family_id = ? AND revoked_at IS NULL`, now, family); err != nil {
			return err
		}
		if err := tx.Commit(); err != nil {
			return err
		}
		return bad
	}
	if expires < now {
		return bad
	}
	if _, err := tx.ExecContext(ctx, `UPDATE refresh_tokens SET revoked_at = ? WHERE id = ?`, now, id); err != nil {
		return err
	}
	sess, err := s.newSession(ctx, tx, uid, family)
	if err != nil {
		return err
	}
	if err := tx.Commit(); err != nil {
		return err
	}
	writeJSON(w, http.StatusOK, sess)
	return nil
}

func (s *server) logout(w http.ResponseWriter, r *http.Request) error {
	var in struct {
		RefreshToken string `json:"refreshToken"`
	}
	if err := readJSON(r, &in); err != nil {
		return err
	}
	if _, err := s.db.ExecContext(r.Context(),
		`UPDATE refresh_tokens SET revoked_at = ? WHERE token_hash = ? AND revoked_at IS NULL`,
		nowMs(), hashToken(in.RefreshToken)); err != nil {
		return err
	}
	w.WriteHeader(http.StatusNoContent)
	return nil
}

// ---- Google ----

func (s *server) googleLogin(w http.ResponseWriter, r *http.Request) error {
	var in struct {
		IDToken string `json:"idToken"`
	}
	if err := readJSON(r, &in); err != nil {
		return err
	}
	g, err := s.verifyGoogle(r.Context(), in.IDToken)
	if err != nil {
		return fail(http.StatusUnauthorized, "invalid_google_token", "Google sign-in could not be verified.")
	}
	email, err := cleanEmail(g.Email)
	if err != nil {
		return fail(http.StatusUnauthorized, "invalid_google_token", "Google sign-in could not be verified.")
	}

	ctx := r.Context()
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()

	var id string
	err = tx.QueryRowContext(ctx, `SELECT id FROM users WHERE google_sub = ?`, g.Sub).Scan(&id)
	if errors.Is(err, sql.ErrNoRows) {
		var verified sql.NullInt64
		err = tx.QueryRowContext(ctx, `SELECT id, email_verified_at FROM users WHERE email = ?`, email).Scan(&id, &verified)
		switch {
		case err == nil:
			// Link to the existing account. If its e-mail was never verified, someone else may
			// have registered it with a password they know, so that password is dropped.
			if !verified.Valid {
				if _, err := tx.ExecContext(ctx, `UPDATE users SET password_hash = NULL WHERE id = ?`, id); err != nil {
					return err
				}
				if _, err := tx.ExecContext(ctx, `UPDATE refresh_tokens SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL`, nowMs(), id); err != nil {
					return err
				}
			}
			_, err = tx.ExecContext(ctx,
				`UPDATE users SET google_sub = ?, email_verified_at = COALESCE(email_verified_at, ?) WHERE id = ?`,
				g.Sub, nowMs(), id)
		case errors.Is(err, sql.ErrNoRows):
			id = randHex(16)
			now := nowMs()
			_, err = tx.ExecContext(ctx,
				`INSERT INTO users (id, email, email_verified_at, google_sub, display_name, created_at) VALUES (?, ?, ?, ?, ?, ?)`,
				id, email, now, g.Sub, displayNameOr(g.Name, email), now)
		}
	}
	if err != nil {
		return err
	}
	sess, err := s.newSession(ctx, tx, id, randHex(16))
	if err != nil {
		return err
	}
	if err := tx.Commit(); err != nil {
		return err
	}
	writeJSON(w, http.StatusOK, sess)
	return nil
}

// ---- verification and reset ----

// sendEmailToken replaces any pending token of that purpose and mails a fresh link. A failed
// send is logged, never returned: the user can always tap "resend".
func (s *server) sendEmailToken(ctx context.Context, uid, email, name, purpose string) {
	if !s.takeMailQuota() {
		return
	}
	token := randToken()
	ttl := verifyTTL
	if purpose == "reset" {
		ttl = resetTTL
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return
	}
	defer tx.Rollback()
	if _, err := tx.ExecContext(ctx, `DELETE FROM email_tokens WHERE user_id = ? AND purpose = ? AND used_at IS NULL`, uid, purpose); err != nil {
		return
	}
	if _, err := tx.ExecContext(ctx,
		`INSERT INTO email_tokens (id, user_id, purpose, token_hash, expires_at) VALUES (?, ?, ?, ?, ?)`,
		randHex(16), uid, purpose, hashToken(token), time.Now().Add(ttl).UnixMilli()); err != nil {
		return
	}
	if err := tx.Commit(); err != nil {
		return
	}

	var subject, htmlBody, textBody string
	if purpose == "verify" {
		subject, htmlBody, textBody = verifyMail(name, email, s.cfg.publicURL+"/v1/auth/verify?token="+token, s.cfg.publicURL)
	} else {
		subject, htmlBody, textBody = resetMail(name, email, s.cfg.publicURL+"/v1/auth/reset?token="+token, s.cfg.publicURL)
	}
	// Sent in the background so a slow mail provider never holds up sign-up; one retry covers a
	// transient timeout. A failure is only logged: the user can always tap "resend".
	send := func() {
		err := s.mail(email, subject, htmlBody, textBody)
		if err != nil {
			time.Sleep(2 * time.Second)
			err = s.mail(email, subject, htmlBody, textBody)
		}
		if err != nil {
			logf("mail to %s failed: %v", email, err)
		}
	}
	if s.syncMail {
		send()
	} else {
		go send()
	}
}

// takeMailQuota enforces our own daily cap, below Brevo's, so a flood cannot burn the allowance.
// ponytail: counts in memory and resets on restart.
func (s *server) takeMailQuota() bool {
	s.mailMu.Lock()
	defer s.mailMu.Unlock()
	day := time.Now().UTC().Format("2006-01-02")
	if day != s.mailDay {
		s.mailDay, s.mailCount = day, 0
	}
	if s.mailCount >= dailyMailCap {
		return false
	}
	s.mailCount++
	return true
}

// consumeToken marks a single-use token used and returns its user, atomically.
func consumeToken(ctx context.Context, q dbtx, token, purpose string) (string, error) {
	var uid string
	err := q.QueryRowContext(ctx,
		`UPDATE email_tokens SET used_at = ? WHERE token_hash = ? AND purpose = ? AND used_at IS NULL AND expires_at > ? RETURNING user_id`,
		nowMs(), hashToken(token), purpose, nowMs()).Scan(&uid)
	return uid, err
}

func (s *server) verifyEmail(w http.ResponseWriter, r *http.Request) error {
	uid, err := consumeToken(r.Context(), s.db, r.URL.Query().Get("token"), "verify")
	if errors.Is(err, sql.ErrNoRows) {
		page(w, http.StatusBadRequest, "Link expired", "This verification link is invalid or has already been used. Open the app and tap Resend.")
		return nil
	}
	if err != nil {
		return err
	}
	if _, err := s.db.ExecContext(r.Context(), `UPDATE users SET email_verified_at = COALESCE(email_verified_at, ?) WHERE id = ?`, nowMs(), uid); err != nil {
		return err
	}
	page(w, http.StatusOK, "Email verified", "You're all set. Return to the Gaali Finder app.")
	return nil
}

func (s *server) resendVerification(w http.ResponseWriter, r *http.Request, uid string) error {
	if !s.lim.allow("resend|"+uid, 3, time.Hour) {
		return errTooMany
	}
	u, err := loadUser(r.Context(), s.db, uid)
	if err != nil {
		return err
	}
	if !u.EmailVerified {
		s.sendEmailToken(r.Context(), u.ID, u.Email, u.DisplayName, "verify")
	}
	w.WriteHeader(http.StatusNoContent)
	return nil
}

// forgot always answers 202 so it cannot be used to discover which e-mails have accounts.
func (s *server) forgot(w http.ResponseWriter, r *http.Request) error {
	var in struct{ Email string }
	if err := readJSON(r, &in); err != nil {
		return err
	}
	email := strings.ToLower(strings.TrimSpace(in.Email))
	if s.lim.allow("forgot-email|"+email, 3, time.Hour) {
		var id, name string
		err := s.db.QueryRowContext(r.Context(), `SELECT id, display_name FROM users WHERE email = ?`, email).Scan(&id, &name)
		if err == nil {
			s.sendEmailToken(r.Context(), id, email, name, "reset")
		} else if !errors.Is(err, sql.ErrNoRows) {
			return err
		}
	}
	writeJSON(w, http.StatusAccepted, map[string]string{"status": "ok"})
	return nil
}

func (s *server) resetPage(w http.ResponseWriter, r *http.Request) error {
	token := r.URL.Query().Get("token")
	var n int
	err := s.db.QueryRowContext(r.Context(),
		`SELECT count(*) FROM email_tokens WHERE token_hash = ? AND purpose = 'reset' AND used_at IS NULL AND expires_at > ?`,
		hashToken(token), nowMs()).Scan(&n)
	if err != nil {
		return err
	}
	if n == 0 {
		page(w, http.StatusBadRequest, "Link expired", "This reset link is invalid or has already been used. Request a new one from the app.")
		return nil
	}
	page(w, http.StatusOK, "Choose a new password", fmt.Sprintf(
		`<form method="post" action="/v1/auth/reset">
<input type="hidden" name="token" value="%s">
<input type="password" name="password" minlength="10" maxlength="128" required placeholder="New password (10+ characters)" autofocus>
<button type="submit">Set password</button></form>`, html.EscapeString(token)))
	return nil
}

func (s *server) resetSubmit(w http.ResponseWriter, r *http.Request) error {
	r.Body = http.MaxBytesReader(w, r.Body, 16<<10)
	if err := r.ParseForm(); err != nil {
		return fail(http.StatusBadRequest, "invalid_request", "Malformed form.")
	}
	pw := r.PostForm.Get("password")
	if n := len(pw); n < 10 || n > 128 {
		page(w, http.StatusBadRequest, "Password too short", "Use a password of 10 to 128 characters. Go back and try again.")
		return nil
	}
	ctx := r.Context()
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	uid, err := consumeToken(ctx, tx, r.PostForm.Get("token"), "reset")
	if errors.Is(err, sql.ErrNoRows) {
		page(w, http.StatusBadRequest, "Link expired", "This reset link is invalid or has already been used. Request a new one from the app.")
		return nil
	}
	if err != nil {
		return err
	}
	// Following the e-mailed link proves control of the address, so it also counts as verifying it.
	if _, err := tx.ExecContext(ctx, `UPDATE users SET password_hash = ?, email_verified_at = COALESCE(email_verified_at, ?) WHERE id = ?`, hashPassword(pw), nowMs(), uid); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `UPDATE refresh_tokens SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL`, nowMs(), uid); err != nil {
		return err
	}
	if err := tx.Commit(); err != nil {
		return err
	}
	page(w, http.StatusOK, "Password updated", "Sign in with your new password in the Gaali Finder app.")
	return nil
}
