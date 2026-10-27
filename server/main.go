// Command server is the Gaali Finder API: auth, clips, reactions and audio files on one SQLite
// file plus one directory. It listens on localhost; Caddy terminates TLS in front of it.
package main

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"
)

const (
	maxFileBytes        = 204_800   // must match StorageQuota in the Android app
	freeTotalBytes      = 10 << 20  // 10 MB
	defaultPremiumBytes = 100 << 20 // 100 MB
	accessTTL           = 15 * time.Minute
	refreshTTL          = 365 * 24 * time.Hour
	verifyTTL           = 24 * time.Hour
	resetTTL            = time.Hour
	dailyMailCap        = 250 // Brevo's free plan allows 300/day
)

type config struct {
	addr           string
	dataDir        string
	publicURL      string // no trailing slash; used in e-mailed links
	jwtSecret      []byte
	googleClientID string
	resendKey      string // preferred mail provider: leaves links untouched
	brevoKey       string // fallback provider; neither key set = log mails instead of sending
	replyTo        string // optional Reply-To so recipients can answer a "no-reply" style sender
	mailFrom       string
	mailFromName   string
}

func loadConfig() config {
	get := func(k, def string) string {
		if v := os.Getenv(k); v != "" {
			return v
		}
		return def
	}
	c := config{
		addr:           get("ADDR", "127.0.0.1:8080"),
		dataDir:        get("DATA_DIR", "./data"),
		publicURL:      strings.TrimRight(get("PUBLIC_URL", "http://localhost:8080"), "/"),
		jwtSecret:      []byte(os.Getenv("JWT_SECRET")),
		googleClientID: os.Getenv("GOOGLE_WEB_CLIENT_ID"),
		resendKey:      os.Getenv("RESEND_API_KEY"),
		brevoKey:       os.Getenv("BREVO_API_KEY"),
		replyTo:        os.Getenv("MAIL_REPLY_TO"),
		mailFrom:       get("MAIL_FROM", "no-reply@example.com"),
		mailFromName:   get("MAIL_FROM_NAME", "Gaali Finder"),
	}
	if len(c.jwtSecret) < 32 {
		log.Fatal("JWT_SECRET must be set to at least 32 characters")
	}
	return c
}

type server struct {
	cfg      config
	db       *sql.DB
	audioDir string
	lim      *limiter

	// Swappable so tests need neither Brevo nor Google.
	mail         func(to, subject, htmlBody, textBody string) error
	verifyGoogle func(ctx context.Context, idToken string) (googleClaims, error)

	syncMail bool // tests only: send inside the request so they can read the mail straight away

	mailMu    sync.Mutex
	mailDay   string
	mailCount int
}

func newServer(cfg config, db *sql.DB) (*server, error) {
	s := &server{cfg: cfg, db: db, audioDir: filepath.Join(cfg.dataDir, "audio"), lim: &limiter{hits: map[string][]time.Time{}}}
	if err := os.MkdirAll(s.audioDir, 0o755); err != nil {
		return nil, err
	}
	switch {
	case cfg.resendKey != "" && cfg.brevoKey != "":
		s.mail = s.resendThenBrevo // lets Brevo cover for Resend until its domain is verified
	case cfg.resendKey != "":
		s.mail = s.resendSend
	case cfg.brevoKey != "":
		s.mail = s.brevoSend
	default:
		s.mail = func(to, subject, _, text string) error {
			log.Printf("MAIL (no RESEND_API_KEY or BREVO_API_KEY) to=%s subject=%q\n%s", to, subject, text)
			return nil
		}
	}
	s.verifyGoogle = newGoogleVerifier(cfg.googleClientID).verify
	return s, nil
}

func (s *server) routes() http.Handler {
	m := http.NewServeMux()
	m.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) { w.Write([]byte("ok")) })
	m.HandleFunc("GET /files/{key}", s.serveFile)
	m.HandleFunc("GET /{$}", staticPage("index.html"))
	m.HandleFunc("GET /privacy", staticPage("privacy.html"))
	m.HandleFunc("GET /logo.png", logo())
	m.HandleFunc("GET /terms", staticPage("terms.html"))

	m.HandleFunc("POST /v1/auth/register", s.public("register", 10, s.register))
	m.HandleFunc("POST /v1/auth/login", s.public("login", 20, s.login))
	m.HandleFunc("POST /v1/auth/google", s.public("google", 20, s.googleLogin))
	m.HandleFunc("POST /v1/auth/refresh", s.public("refresh", 60, s.refresh))
	m.HandleFunc("POST /v1/auth/logout", s.public("logout", 60, s.logout))
	m.HandleFunc("POST /v1/auth/forgot", s.public("forgot", 10, s.forgot))
	m.HandleFunc("GET /v1/auth/verify", s.public("verify", 30, s.verifyEmail))
	m.HandleFunc("GET /v1/auth/reset", s.public("resetpage", 30, s.resetPage))
	m.HandleFunc("POST /v1/auth/reset", s.public("reset", 10, s.resetSubmit))
	m.HandleFunc("POST /v1/auth/resend-verification", s.authed(s.resendVerification))

	m.HandleFunc("GET /v1/me", s.authed(s.me))
	m.HandleFunc("GET /v1/me/reactions", s.authed(s.myReactions))
	m.HandleFunc("GET /v1/clips", s.h(s.listClips))
	m.HandleFunc("POST /v1/clips", s.authed(s.uploadClip))
	m.HandleFunc("DELETE /v1/clips/{id}", s.authed(s.deleteClip))
	m.HandleFunc("PUT /v1/clips/{id}/reaction", s.authed(s.setReaction))
	return securityHeaders(m)
}

func main() {
	cfg := loadConfig()
	if err := os.MkdirAll(cfg.dataDir, 0o755); err != nil {
		log.Fatal(err)
	}
	db, err := openDB(filepath.Join(cfg.dataDir, "gaali.db"))
	if err != nil {
		log.Fatal(err)
	}
	defer db.Close()
	s, err := newServer(cfg, db)
	if err != nil {
		log.Fatal(err)
	}

	srv := &http.Server{
		Addr:              cfg.addr,
		Handler:           s.routes(),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       2 * time.Minute,
	}
	go func() {
		stop := make(chan os.Signal, 1)
		signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
		<-stop
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		srv.Shutdown(ctx)
	}()
	log.Printf("listening on %s", cfg.addr)
	if err := srv.ListenAndServe(); !errors.Is(err, http.ErrServerClosed) {
		log.Fatal(err)
	}
}

// ---- errors and handler plumbing ----

// apiErr is the one error shape clients see: {"error": code, "message": text, ...extra}.
type apiErr struct {
	status int
	code   string
	msg    string
	extra  map[string]any
}

func (e *apiErr) Error() string { return e.code + ": " + e.msg }

func fail(status int, code, msg string) *apiErr { return &apiErr{status: status, code: code, msg: msg} }

var errTooMany = fail(http.StatusTooManyRequests, "rate_limited", "Too many attempts. Try again in a bit.")

// h turns a handler that returns an error into an http.HandlerFunc.
func (s *server) h(fn func(http.ResponseWriter, *http.Request) error) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		err := fn(w, r)
		if err == nil {
			return
		}
		var ae *apiErr
		if !errors.As(err, &ae) {
			log.Printf("%s %s: %v", r.Method, r.URL.Path, err)
			ae = fail(http.StatusInternalServerError, "internal", "Something went wrong on our side.")
		}
		body := map[string]any{"error": ae.code, "message": ae.msg}
		for k, v := range ae.extra {
			body[k] = v
		}
		writeJSON(w, ae.status, body)
	}
}

// public is h plus a per-IP rate limit of perMinute requests for the named route.
func (s *server) public(name string, perMinute int, fn func(http.ResponseWriter, *http.Request) error) http.HandlerFunc {
	return s.h(func(w http.ResponseWriter, r *http.Request) error {
		if !s.lim.allow(name+"|"+clientIP(r), perMinute, time.Minute) {
			return errTooMany
		}
		return fn(w, r)
	})
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

func readJSON(r *http.Request, v any) error {
	r.Body = http.MaxBytesReader(nil, r.Body, 64<<10)
	if err := json.NewDecoder(r.Body).Decode(v); err != nil {
		return fail(http.StatusBadRequest, "invalid_request", "Malformed request body.")
	}
	return nil
}

// clientIP trusts X-Forwarded-For because the server only listens on localhost behind Caddy,
// which appends the real peer as the last entry.
func clientIP(r *http.Request) string {
	if x := r.Header.Get("X-Forwarded-For"); x != "" {
		parts := strings.Split(x, ",")
		return strings.TrimSpace(parts[len(parts)-1])
	}
	host, _, _ := net.SplitHostPort(r.RemoteAddr)
	return host
}

// limiter is a sliding-window counter. ponytail: in-memory, resets on restart; fine for one VM.
type limiter struct {
	off  bool // tests only
	mu   sync.Mutex
	hits map[string][]time.Time
}

func (l *limiter) allow(key string, n int, per time.Duration) bool {
	if l.off {
		return true
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	now := time.Now()
	cut := now.Add(-per)
	kept := l.hits[key][:0]
	for _, t := range l.hits[key] {
		if t.After(cut) {
			kept = append(kept, t)
		}
	}
	if len(kept) >= n {
		l.hits[key] = kept
		return false
	}
	l.hits[key] = append(kept, now)
	if len(l.hits) > 10_000 { // drop keys idle for over an hour so the map cannot grow forever
		old := now.Add(-time.Hour)
		for k, v := range l.hits {
			if v[len(v)-1].Before(old) {
				delete(l.hits, k)
			}
		}
	}
	return true
}

func randBytes(n int) []byte {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		panic(err) // the OS entropy source failing is not recoverable
	}
	return b
}

func randHex(n int) string { return hex.EncodeToString(randBytes(n)) }
func randToken() string    { return base64.RawURLEncoding.EncodeToString(randBytes(32)) }
func nowMs() int64         { return time.Now().UnixMilli() }

// securityHeaders sets the browser-facing defences on every response. The API serves JSON, audio
// and a few small HTML pages, so the policy allows nothing beyond those pages' inline styles and
// same-origin images and form posts, and forbids framing and content-type sniffing.
func securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("X-Frame-Options", "DENY")
		h.Set("Referrer-Policy", "no-referrer")
		h.Set("Strict-Transport-Security", "max-age=31536000")
		h.Set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; img-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'")
		next.ServeHTTP(w, r)
	})
}
