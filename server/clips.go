package main

import (
	"crypto/sha256"
	"database/sql"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// audioMIME is the upload allowlist, keyed by file extension.
// ponytail: trusts the extension rather than sniffing the bytes; add a magic-byte check if abuse appears.
var audioMIME = map[string]string{
	".mp3": "audio/mpeg", ".m4a": "audio/mp4", ".mp4": "audio/mp4", ".aac": "audio/aac",
	".ogg": "audio/ogg", ".oga": "audio/ogg", ".wav": "audio/wav", ".opus": "audio/opus", ".flac": "audio/flac",
}

type clipDTO struct {
	ID           string `json:"id"`
	FileID       string `json:"fileId"` // storage key; the file lives at /files/<fileId>
	FileName     string `json:"fileName"`
	UploaderID   string `json:"uploaderId"`
	UploaderName string `json:"uploaderName"`
	SizeBytes    int64  `json:"sizeBytes"`
	CreatedAt    int64  `json:"createdAt"` // unix ms
	LikeCount    int    `json:"likeCount"`
	DislikeCount int    `json:"dislikeCount"`
}

const clipColumns = `id, storage_key, file_name, uploader_id, uploader_name, size_bytes, created_at, like_count, dislike_count`

type rowScanner interface{ Scan(dest ...any) error }

func scanClip(r rowScanner) (clipDTO, error) {
	var c clipDTO
	err := r.Scan(&c.ID, &c.FileID, &c.FileName, &c.UploaderID, &c.UploaderName, &c.SizeBytes, &c.CreatedAt, &c.LikeCount, &c.DislikeCount)
	return c, err
}

// listClips pages newest-first. The cursor is "<createdAtMs>.<id>" of the last clip returned.
func (s *server) listClips(w http.ResponseWriter, r *http.Request) error {
	limit := 100
	if v, err := strconv.Atoi(r.URL.Query().Get("limit")); err == nil && v > 0 && v <= 200 {
		limit = v
	}
	var curMs int64
	var curID string
	if c := r.URL.Query().Get("cursor"); c != "" {
		ms, id, ok := strings.Cut(c, ".")
		n, err := strconv.ParseInt(ms, 10, 64)
		if !ok || err != nil {
			return fail(http.StatusBadRequest, "invalid_request", "Bad cursor.")
		}
		curMs, curID = n, id
	}
	rows, err := s.db.QueryContext(r.Context(),
		`SELECT `+clipColumns+` FROM clips
		 WHERE (? = 0 OR created_at < ? OR (created_at = ? AND id < ?))
		 ORDER BY created_at DESC, id DESC LIMIT ?`,
		curMs, curMs, curMs, curID, limit+1)
	if err != nil {
		return err
	}
	defer rows.Close()
	clips := []clipDTO{}
	for rows.Next() {
		c, err := scanClip(rows)
		if err != nil {
			return err
		}
		clips = append(clips, c)
	}
	if err := rows.Err(); err != nil {
		return err
	}
	next := ""
	if len(clips) > limit {
		clips = clips[:limit]
		last := clips[limit-1]
		next = fmt.Sprintf("%d.%s", last.CreatedAt, last.ID)
	}
	writeJSON(w, http.StatusOK, map[string]any{"clips": clips, "nextCursor": next})
	return nil
}

func planLimit(r *http.Request, q dbtx, uid string) (limit int64, premium bool, err error) {
	var isPremium, custom int64
	err = q.QueryRowContext(r.Context(), `SELECT is_premium, storage_limit_bytes FROM user_plans WHERE user_id = ?`, uid).Scan(&isPremium, &custom)
	if errors.Is(err, sql.ErrNoRows) {
		return freeTotalBytes, false, nil
	}
	if err != nil {
		return 0, false, err
	}
	if isPremium != 1 {
		return freeTotalBytes, false, nil
	}
	if custom <= 0 {
		custom = defaultPremiumBytes
	}
	return custom, true, nil
}

func usedBytes(r *http.Request, q dbtx, uid string) (n int64, err error) {
	err = q.QueryRowContext(r.Context(), `SELECT COALESCE(SUM(size_bytes), 0) FROM clips WHERE uploader_id = ?`, uid).Scan(&n)
	return
}

func (s *server) me(w http.ResponseWriter, r *http.Request, uid string) error {
	u, err := loadUser(r.Context(), s.db, uid)
	if err != nil {
		return err
	}
	limit, premium, err := planLimit(r, s.db, uid)
	if err != nil {
		return err
	}
	used, err := usedBytes(r, s.db, uid)
	if err != nil {
		return err
	}
	writeJSON(w, http.StatusOK, map[string]any{"user": u, "isPremium": premium, "usedBytes": used, "limitBytes": limit})
	return nil
}

// uploadClip validates, enforces the quota inside one write transaction, then stores the file.
// The size is measured from the bytes received, so a client cannot under-report it.
func (s *server) uploadClip(w http.ResponseWriter, r *http.Request, uid string) error {
	ctx := r.Context()
	var name string
	var verified sql.NullInt64
	err := s.db.QueryRowContext(ctx, `SELECT display_name, email_verified_at FROM users WHERE id = ?`, uid).Scan(&name, &verified)
	if errors.Is(err, sql.ErrNoRows) {
		return fail(http.StatusUnauthorized, "unauthorized", "Sign in again.")
	}
	if err != nil {
		return err
	}
	if !verified.Valid {
		return fail(http.StatusForbidden, "email_not_verified", "Verify your email before uploading.")
	}

	tooLarge := func(size int64) *apiErr {
		e := fail(http.StatusRequestEntityTooLarge, "file_too_large", "Clips can be at most 200 KB.")
		e.extra = map[string]any{"sizeBytes": size, "maxBytes": maxFileBytes}
		return e
	}
	r.Body = http.MaxBytesReader(w, r.Body, maxFileBytes+64<<10) // file plus multipart overhead
	if err := r.ParseMultipartForm(1 << 20); err != nil {
		var tl *http.MaxBytesError
		if errors.As(err, &tl) {
			return tooLarge(0)
		}
		return fail(http.StatusBadRequest, "invalid_request", "Expected a multipart upload with a \"file\" part.")
	}
	defer r.MultipartForm.RemoveAll()
	f, hdr, err := r.FormFile("file")
	if err != nil {
		return fail(http.StatusBadRequest, "invalid_request", "Expected a multipart upload with a \"file\" part.")
	}
	defer f.Close()
	data, err := io.ReadAll(io.LimitReader(f, maxFileBytes+1))
	if err != nil {
		return err
	}
	if len(data) == 0 {
		return fail(http.StatusBadRequest, "empty_file", "The file is empty.")
	}
	if len(data) > maxFileBytes {
		return tooLarge(int64(len(data)))
	}

	fileName := r.FormValue("fileName")
	if fileName == "" {
		fileName = hdr.Filename
	}
	fileName = strings.TrimSpace(path.Base(strings.ReplaceAll(fileName, "\\", "/")))
	if r := []rune(fileName); len(r) > 120 {
		fileName = string(r[:120])
	}
	ext := strings.ToLower(filepath.Ext(fileName))
	mime, ok := audioMIME[ext]
	if !ok {
		return fail(http.StatusUnsupportedMediaType, "unsupported_media", "Only audio files are accepted.")
	}

	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	limit, premium, err := planLimit(r, tx, uid)
	if err != nil {
		return err
	}
	used, err := usedBytes(r, tx, uid)
	if err != nil {
		return err
	}
	size := int64(len(data))
	if used+size > limit {
		e := fail(http.StatusPaymentRequired, "quota_exceeded", "This clip would exceed your storage allowance.")
		e.extra = map[string]any{"usedBytes": used, "limitBytes": limit, "isPremium": premium}
		return e
	}

	key := randHex(16) + ext
	filePath := filepath.Join(s.audioDir, key)
	if err := os.WriteFile(filePath, data, 0o644); err != nil {
		return err
	}
	committed := false
	defer func() {
		if !committed {
			os.Remove(filePath)
		}
	}()
	sum := sha256.Sum256(data)
	clip := clipDTO{ID: randHex(16), FileID: key, FileName: fileName, UploaderID: uid, UploaderName: name, SizeBytes: size, CreatedAt: nowMs()}
	if _, err := tx.ExecContext(ctx,
		`INSERT INTO clips (id, uploader_id, uploader_name, file_name, storage_key, mime_type, size_bytes, sha256, created_at)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		clip.ID, uid, name, fileName, key, mime, size, sum[:], clip.CreatedAt); err != nil {
		return err
	}
	if err := tx.Commit(); err != nil {
		return err
	}
	committed = true
	writeJSON(w, http.StatusCreated, clip)
	return nil
}

func (s *server) deleteClip(w http.ResponseWriter, r *http.Request, uid string) error {
	ctx := r.Context()
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var owner, key string
	err = tx.QueryRowContext(ctx, `SELECT uploader_id, storage_key FROM clips WHERE id = ?`, r.PathValue("id")).Scan(&owner, &key)
	if errors.Is(err, sql.ErrNoRows) {
		return fail(http.StatusNotFound, "not_found", "That clip no longer exists.")
	}
	if err != nil {
		return err
	}
	if owner != uid {
		return fail(http.StatusForbidden, "forbidden", "Only the uploader can delete a clip.")
	}
	if _, err := tx.ExecContext(ctx, `DELETE FROM clips WHERE id = ?`, r.PathValue("id")); err != nil {
		return err
	}
	if err := tx.Commit(); err != nil {
		return err
	}
	// The row is gone, so a failed unlink only leaves an invisible orphan file.
	if err := os.Remove(filepath.Join(s.audioDir, key)); err != nil && !errors.Is(err, os.ErrNotExist) {
		logf("removing %s: %v", key, err)
	}
	w.WriteHeader(http.StatusNoContent)
	return nil
}

func (s *server) myReactions(w http.ResponseWriter, r *http.Request, uid string) error {
	rows, err := s.db.QueryContext(r.Context(), `SELECT clip_id, kind FROM reactions WHERE user_id = ?`, uid)
	if err != nil {
		return err
	}
	defer rows.Close()
	out := map[string]string{}
	for rows.Next() {
		var clip, kind string
		if err := rows.Scan(&clip, &kind); err != nil {
			return err
		}
		out[clip] = kind
	}
	if err := rows.Err(); err != nil {
		return err
	}
	writeJSON(w, http.StatusOK, out)
	return nil
}

// setReaction moves the caller's reaction to like, dislike or none. One row per (clip, user)
// makes like and dislike exclusive; triggers keep the clip's counts in step.
func (s *server) setReaction(w http.ResponseWriter, r *http.Request, uid string) error {
	var in struct{ Kind string }
	if err := readJSON(r, &in); err != nil {
		return err
	}
	if in.Kind != "like" && in.Kind != "dislike" && in.Kind != "none" {
		return fail(http.StatusBadRequest, "invalid_request", `kind must be "like", "dislike" or "none".`)
	}
	ctx, clipID := r.Context(), r.PathValue("id")
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var exists int
	if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM clips WHERE id = ?`, clipID).Scan(&exists); err != nil {
		return err
	}
	if exists == 0 {
		return fail(http.StatusNotFound, "not_found", "That clip no longer exists.")
	}
	if in.Kind == "none" {
		_, err = tx.ExecContext(ctx, `DELETE FROM reactions WHERE clip_id = ? AND user_id = ?`, clipID, uid)
	} else {
		_, err = tx.ExecContext(ctx,
			`INSERT INTO reactions (clip_id, user_id, kind, updated_at) VALUES (?, ?, ?, ?)
			 ON CONFLICT (clip_id, user_id) DO UPDATE SET kind = excluded.kind, updated_at = excluded.updated_at`,
			clipID, uid, in.Kind, nowMs())
	}
	if err != nil {
		return err
	}
	var likes, dislikes int
	if err := tx.QueryRowContext(ctx, `SELECT like_count, dislike_count FROM clips WHERE id = ?`, clipID).Scan(&likes, &dislikes); err != nil {
		return err
	}
	if err := tx.Commit(); err != nil {
		return err
	}
	writeJSON(w, http.StatusOK, map[string]int{"likeCount": likes, "dislikeCount": dislikes})
	return nil
}

var storageKeyRE = regexp.MustCompile(`^[0-9a-f]{32}\.[a-z0-9]{2,5}$`)

// serveFile streams a stored clip. Keys are random and never reused, so caching is forever.
func (s *server) serveFile(w http.ResponseWriter, r *http.Request) {
	key := r.PathValue("key")
	if !storageKeyRE.MatchString(key) {
		http.NotFound(w, r)
		return
	}
	if mime, ok := audioMIME[filepath.Ext(key)]; ok {
		w.Header().Set("Content-Type", mime)
	}
	w.Header().Set("Cache-Control", "public, max-age="+strconv.Itoa(int(365*24*time.Hour/time.Second))+", immutable")
	http.ServeFile(w, r, filepath.Join(s.audioDir, key))
}
