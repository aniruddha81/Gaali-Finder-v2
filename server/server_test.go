package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"testing"
)

type env struct {
	t    *testing.T
	s    *server
	ts   *httptest.Server
	mu   sync.Mutex
	mail []string // text bodies, newest last
}

func newEnv(t *testing.T) *env {
	t.Helper()
	dir := t.TempDir()
	db, err := openDB(filepath.Join(dir, "gaali.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	s, err := newServer(config{dataDir: dir, publicURL: "http://test", jwtSecret: []byte(strings.Repeat("k", 32))}, db)
	if err != nil {
		t.Fatal(err)
	}
	e := &env{t: t, s: s}
	s.syncMail = true
	s.lim.off = true // tests register dozens of users from one IP; TestRateLimit turns it back on
	s.mail = func(to, subject, _, text string) error {
		e.mu.Lock()
		defer e.mu.Unlock()
		e.mail = append(e.mail, text)
		return nil
	}
	e.ts = httptest.NewServer(s.routes())
	t.Cleanup(e.ts.Close)
	return e
}

var linkRE = regexp.MustCompile(`http://test(/v1/auth/\w+\?token=[\w-]+)`)

func (e *env) lastLink() string {
	e.mu.Lock()
	defer e.mu.Unlock()
	if len(e.mail) == 0 {
		e.t.Fatal("no mail was sent")
	}
	m := linkRE.FindStringSubmatch(e.mail[len(e.mail)-1])
	if m == nil {
		e.t.Fatal("no link in mail")
	}
	return m[1]
}

// call sends a JSON request and returns the status and decoded JSON object.
func (e *env) call(method, path, token string, body any) (int, map[string]any) {
	e.t.Helper()
	var rd io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rd = bytes.NewReader(b)
	}
	req, _ := http.NewRequest(method, e.ts.URL+path, rd)
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	return e.do(req)
}

func (e *env) do(req *http.Request) (int, map[string]any) {
	e.t.Helper()
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		e.t.Fatal(err)
	}
	defer resp.Body.Close()
	out := map[string]any{}
	raw, _ := io.ReadAll(resp.Body)
	json.Unmarshal(raw, &out)
	return resp.StatusCode, out
}

func (e *env) upload(token, name string, size int) (int, map[string]any) {
	e.t.Helper()
	var buf bytes.Buffer
	mw := multipart.NewWriter(&buf)
	fw, _ := mw.CreateFormFile("file", name)
	fw.Write(bytes.Repeat([]byte{7}, size))
	mw.WriteField("fileName", name)
	mw.Close()
	req, _ := http.NewRequest("POST", e.ts.URL+"/v1/clips", &buf)
	req.Header.Set("Content-Type", mw.FormDataContentType())
	req.Header.Set("Authorization", "Bearer "+token)
	return e.do(req)
}

func str(m map[string]any, k string) string { s, _ := m[k].(string); return s }
func access(m map[string]any) string        { return str(m, "accessToken") }

func (e *env) register(email string) map[string]any {
	e.t.Helper()
	st, m := e.call("POST", "/v1/auth/register", "", map[string]string{"email": email, "password": "correct horse battery", "displayName": "Tester"})
	if st != 201 {
		e.t.Fatalf("register %s: %d %v", email, st, m)
	}
	return m
}

func (e *env) verify(link string) {
	e.t.Helper()
	resp, err := http.Get(e.ts.URL + link)
	if err != nil || resp.StatusCode != 200 {
		e.t.Fatalf("verify link failed: %v %v", err, resp)
	}
	resp.Body.Close()
}

func TestAuthFlow(t *testing.T) {
	e := newEnv(t)
	reg := e.register("Ann@Example.com")
	if reg["user"].(map[string]any)["emailVerified"] != false {
		t.Fatal("new account must start unverified")
	}
	if st, _ := e.register2("ann@example.com"); st != 409 {
		t.Fatalf("duplicate e-mail (case-insensitive) should 409, got %d", st)
	}

	// Uploading needs a verified e-mail.
	if st, m := e.upload(access(reg), "a.mp3", 1000); st != 403 || str(m, "error") != "email_not_verified" {
		t.Fatalf("unverified upload: %d %v", st, m)
	}
	e.verify(e.lastLink())
	if st, _ := e.upload(access(reg), "a.mp3", 1000); st != 201 {
		t.Fatalf("verified upload failed: %d", st)
	}
	resp, _ := http.Get(e.ts.URL + e.lastLink()) // the link is single use
	if resp.StatusCode != 400 {
		t.Fatalf("reused verify link should 400, got %d", resp.StatusCode)
	}

	if st, _ := e.call("POST", "/v1/auth/login", "", map[string]string{"email": "ann@example.com", "password": "wrong password!"}); st != 401 {
		t.Fatalf("bad password: %d", st)
	}
	st, login := e.call("POST", "/v1/auth/login", "", map[string]string{"email": "ann@example.com", "password": "correct horse battery"})
	if st != 200 {
		t.Fatalf("login: %d", st)
	}

	// Refresh rotates; replaying the old token kills the family.
	old := str(login, "refreshToken")
	st, rotated := e.call("POST", "/v1/auth/refresh", "", map[string]string{"refreshToken": old})
	if st != 200 || str(rotated, "refreshToken") == old {
		t.Fatalf("refresh: %d %v", st, rotated)
	}
	if st, _ := e.call("POST", "/v1/auth/refresh", "", map[string]string{"refreshToken": old}); st != 401 {
		t.Fatalf("replayed refresh token should 401, got %d", st)
	}
	if st, _ := e.call("POST", "/v1/auth/refresh", "", map[string]string{"refreshToken": str(rotated, "refreshToken")}); st != 401 {
		t.Fatalf("family should be revoked after replay, got %d", st)
	}

	// Forgot -> reset: unknown e-mails look identical, the reset revokes sessions.
	if st, _ := e.call("POST", "/v1/auth/forgot", "", map[string]string{"email": "nobody@example.com"}); st != 202 {
		t.Fatalf("forgot unknown: %d", st)
	}
	_, login = e.call("POST", "/v1/auth/login", "", map[string]string{"email": "ann@example.com", "password": "correct horse battery"})
	e.call("POST", "/v1/auth/forgot", "", map[string]string{"email": "ann@example.com"})
	token := regexp.MustCompile(`token=([\w-]+)`).FindStringSubmatch(e.lastLink())[1]
	resp, err := http.PostForm(e.ts.URL+"/v1/auth/reset", url.Values{"token": {token}, "password": {"a brand new password"}})
	if err != nil || resp.StatusCode != 200 {
		t.Fatalf("reset: %v %v", err, resp)
	}
	if st, _ := e.call("POST", "/v1/auth/refresh", "", map[string]string{"refreshToken": str(login, "refreshToken")}); st != 401 {
		t.Fatalf("reset must revoke existing sessions, got %d", st)
	}
	if st, _ := e.call("POST", "/v1/auth/login", "", map[string]string{"email": "ann@example.com", "password": "a brand new password"}); st != 200 {
		t.Fatalf("login with new password: %d", st)
	}
	resp, _ = http.PostForm(e.ts.URL+"/v1/auth/reset", url.Values{"token": {token}, "password": {"another password!!"}})
	if resp.StatusCode != 400 {
		t.Fatalf("reset link must be single use, got %d", resp.StatusCode)
	}
}

func (e *env) register2(email string) (int, map[string]any) {
	return e.call("POST", "/v1/auth/register", "", map[string]string{"email": email, "password": "correct horse battery"})
}

func TestQuotaBoundary(t *testing.T) {
	e := newEnv(t)
	reg := e.register("q@example.com")
	e.verify(e.lastLink())
	uid := reg["user"].(map[string]any)["id"].(string)
	tok := access(reg)

	// Premium with a 400 KB allowance: two 200 KB clips fit exactly, the third does not.
	if _, err := e.s.db.Exec(`INSERT INTO user_plans (user_id, is_premium, storage_limit_bytes, updated_at) VALUES (?, 1, ?, 0)`, uid, 2*maxFileBytes); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 2; i++ {
		if st, m := e.upload(tok, "full.mp3", maxFileBytes); st != 201 {
			t.Fatalf("upload %d at the cap: %d %v", i, st, m)
		}
	}
	st, m := e.upload(tok, "one-more.mp3", 1)
	if st != 402 || str(m, "error") != "quota_exceeded" || m["usedBytes"].(float64) != 2*maxFileBytes {
		t.Fatalf("over quota: %d %v", st, m)
	}
	if st, m := e.upload(tok, "big.mp3", maxFileBytes+1); st != 413 || str(m, "error") != "file_too_large" {
		t.Fatalf("oversize file: %d %v", st, m)
	}
	if st, _ := e.upload(tok, "notes.txt", 10); st != 415 {
		t.Fatalf("non-audio: %d", st)
	}
	_, me := e.call("GET", "/v1/me", tok, nil)
	if me["usedBytes"].(float64) != 2*maxFileBytes || me["isPremium"] != true {
		t.Fatalf("/me: %v", me)
	}
	// A rejected upload must leave no file behind.
	files, _ := os.ReadDir(e.s.audioDir)
	if len(files) != 2 {
		t.Fatalf("expected 2 stored files, found %d", len(files))
	}
}

func TestConcurrentUploadsRespectQuota(t *testing.T) {
	e := newEnv(t)
	reg := e.register("c@example.com")
	e.verify(e.lastLink())
	tok := access(reg)
	// Free tier is 10 MB = 51 whole 200 KB clips. Fire 60 at once; exactly 51 may land.
	var wg sync.WaitGroup
	var mu sync.Mutex
	ok := 0
	for i := 0; i < 60; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if st, _ := e.upload(tok, "x.mp3", maxFileBytes); st == 201 {
				mu.Lock()
				ok++
				mu.Unlock()
			}
		}()
	}
	wg.Wait()
	if ok != freeTotalBytes/maxFileBytes {
		t.Fatalf("expected %d accepted uploads, got %d", freeTotalBytes/maxFileBytes, ok)
	}
}

func TestReactionsAndDelete(t *testing.T) {
	e := newEnv(t)
	owner := e.register("owner@example.com")
	e.verify(e.lastLink())
	_, clip := e.upload(access(owner), "hi.mp3", 500)
	id, key := str(clip, "id"), str(clip, "fileId")

	// 12 users vote at once; every vote must be counted.
	var wg sync.WaitGroup
	voters := make([]string, 12)
	for i := range voters {
		voters[i] = access(e.register(strings.Repeat("v", i+1) + "@example.com"))
	}
	for _, tok := range voters {
		wg.Add(1)
		go func(tok string) {
			defer wg.Done()
			e.call("PUT", "/v1/clips/"+id+"/reaction", tok, map[string]string{"kind": "like"})
		}(tok)
	}
	wg.Wait()
	_, list := e.call("GET", "/v1/clips", "", nil)
	c := list["clips"].([]any)[0].(map[string]any)
	if c["likeCount"].(float64) != 12 || c["dislikeCount"].(float64) != 0 {
		t.Fatalf("counts after 12 likes: %v", c)
	}

	// Switching flips the count; "none" removes it; repeating a vote changes nothing.
	_, m := e.call("PUT", "/v1/clips/"+id+"/reaction", voters[0], map[string]string{"kind": "dislike"})
	if m["likeCount"].(float64) != 11 || m["dislikeCount"].(float64) != 1 {
		t.Fatalf("after switch: %v", m)
	}
	e.call("PUT", "/v1/clips/"+id+"/reaction", voters[0], map[string]string{"kind": "none"})
	_, m = e.call("PUT", "/v1/clips/"+id+"/reaction", voters[1], map[string]string{"kind": "like"})
	if m["likeCount"].(float64) != 11 || m["dislikeCount"].(float64) != 0 {
		t.Fatalf("after none + repeat: %v", m)
	}
	_, mine := e.call("GET", "/v1/me/reactions", voters[1], nil)
	if mine[id] != "like" {
		t.Fatalf("/me/reactions: %v", mine)
	}
	if st, _ := e.call("PUT", "/v1/clips/nope/reaction", voters[1], map[string]string{"kind": "like"}); st != 404 {
		t.Fatalf("reacting to a missing clip: %d", st)
	}
	if st, _ := e.call("PUT", "/v1/clips/"+id+"/reaction", "", map[string]string{"kind": "like"}); st != 401 {
		t.Fatalf("guest reaction: %d", st)
	}

	// The file is served publicly and immutably; only the owner can delete it.
	resp, _ := http.Get(e.ts.URL + "/files/" + key)
	if resp.StatusCode != 200 || !strings.Contains(resp.Header.Get("Cache-Control"), "immutable") {
		t.Fatalf("file serve: %d %v", resp.StatusCode, resp.Header)
	}
	if st, _ := e.call("DELETE", "/v1/clips/"+id, voters[0], nil); st != 403 {
		t.Fatalf("non-owner delete: %d", st)
	}
	if st, _ := e.call("DELETE", "/v1/clips/"+id, access(owner), nil); st != 204 {
		t.Fatalf("owner delete: %d", st)
	}
	resp, _ = http.Get(e.ts.URL + "/files/" + key)
	if resp.StatusCode != 404 {
		t.Fatalf("deleted file still served: %d", resp.StatusCode)
	}
	var n int
	e.s.db.QueryRow(`SELECT count(*) FROM reactions`).Scan(&n)
	if n != 0 {
		t.Fatalf("reactions should cascade with the clip, %d left", n)
	}
}

func TestListPagination(t *testing.T) {
	e := newEnv(t)
	reg := e.register("p@example.com")
	e.verify(e.lastLink())
	for i := 0; i < 5; i++ {
		e.upload(access(reg), "c.mp3", 10)
	}
	seen := map[string]bool{}
	cursor := ""
	for pages := 0; pages < 10; pages++ {
		_, m := e.call("GET", "/v1/clips?limit=2&cursor="+url.QueryEscape(cursor), "", nil)
		for _, c := range m["clips"].([]any) {
			id := str(c.(map[string]any), "id")
			if seen[id] {
				t.Fatalf("clip %s returned twice", id)
			}
			seen[id] = true
		}
		cursor = str(m, "nextCursor")
		if cursor == "" {
			break
		}
	}
	if len(seen) != 5 {
		t.Fatalf("pagination returned %d of 5 clips", len(seen))
	}
}

func TestGoogleSignIn(t *testing.T) {
	e := newEnv(t)
	claims := googleClaims{Sub: "g-1", Email: "Gina@Example.com", Name: "Gina"}
	e.s.verifyGoogle = func(_ context.Context, tok string) (googleClaims, error) {
		if tok != "good" {
			return googleClaims{}, errors.New("bad token")
		}
		return claims, nil
	}
	if st, _ := e.call("POST", "/v1/auth/google", "", map[string]string{"idToken": "forged"}); st != 401 {
		t.Fatalf("forged token: %d", st)
	}
	st, first := e.call("POST", "/v1/auth/google", "", map[string]string{"idToken": "good"})
	user := first["user"].(map[string]any)
	if st != 200 || user["emailVerified"] != true || user["email"] != "gina@example.com" {
		t.Fatalf("new google user: %d %v", st, first)
	}
	_, again := e.call("POST", "/v1/auth/google", "", map[string]string{"idToken": "good"})
	if again["user"].(map[string]any)["id"] != user["id"] {
		t.Fatal("same Google account must map to the same user")
	}

	// Pre-hijack: a password account registered (unverified) on someone's address loses its
	// password when the real owner signs in with Google.
	e.register("victim@example.com")
	claims = googleClaims{Sub: "g-2", Email: "victim@example.com", Name: "Victim"}
	if st, _ := e.call("POST", "/v1/auth/google", "", map[string]string{"idToken": "good"}); st != 200 {
		t.Fatalf("linking: %d", st)
	}
	if st, _ := e.call("POST", "/v1/auth/login", "", map[string]string{"email": "victim@example.com", "password": "correct horse battery"}); st != 401 {
		t.Fatalf("attacker password must stop working after the owner links Google, got %d", st)
	}
}

func TestRateLimit(t *testing.T) {
	e := newEnv(t)
	e.s.lim.off = false
	var last int
	for i := 0; i < 12; i++ { // register allows 10 per minute per IP
		last, _ = e.call("POST", "/v1/auth/register", "", map[string]string{"email": "bad"})
	}
	if last != 429 {
		t.Fatalf("expected 429 after the limit, got %d", last)
	}
}

func TestSchemaConstraints(t *testing.T) {
	e := newEnv(t)
	reg := e.register("s@example.com")
	uid := reg["user"].(map[string]any)["id"].(string)
	_, err := e.s.db.Exec(`INSERT INTO clips (id, uploader_id, uploader_name, file_name, storage_key, mime_type, size_bytes, sha256, created_at)
		VALUES ('x', ?, 'n', 'f.mp3', 'k', 'audio/mpeg', ?, x'00', 0)`, uid, maxFileBytes+1)
	if err == nil {
		t.Fatal("the database must reject clips over 200 KB on its own")
	}
}

func TestPublicPages(t *testing.T) {
	e := newEnv(t)
	for path, want := range map[string]string{"/": "soundboard", "/privacy": "Privacy Policy", "/terms": "Terms of Service"} {
		resp, err := http.Get(e.ts.URL + path)
		if err != nil || resp.StatusCode != 200 {
			t.Fatalf("%s: %v %v", path, err, resp)
		}
		b, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		if !strings.Contains(string(b), want) {
			t.Fatalf("%s should mention %q", path, want)
		}
	}
	if resp, _ := http.Get(e.ts.URL + "/nope"); resp.StatusCode != 404 {
		t.Fatalf("unknown paths must still 404, got %d", resp.StatusCode)
	}
}

func TestMailContent(t *testing.T) {
	subject, htmlBody, textBody := verifyMail("Ann <b>", "ann@example.com", "https://api.example.com/v1/auth/verify?token=abc", "https://api.example.com")
	if subject == "" || !strings.Contains(htmlBody, "<title>"+subject+"</title>") {
		t.Fatal("HTML needs a real <title>")
	}
	if strings.Count(htmlBody, "https://api.example.com/v1/auth/verify?token=abc") != 2 { // the button's href + the address as plain text
		t.Fatalf("link should be the button plus plain text only: %d", strings.Count(htmlBody, "token=abc"))
	}
	if strings.Count(htmlBody, "<a href=") != 3 { // button, privacy, terms - the visible address must not be a second link
		t.Fatal("unexpected number of links in the HTML")
	}
	if strings.Contains(htmlBody, "<b>") || !strings.Contains(htmlBody, "Ann &lt;b&gt;") {
		t.Fatal("the display name must be HTML-escaped")
	}
	for _, want := range []string{"/privacy", "/terms", "ann@example.com"} {
		if !strings.Contains(htmlBody, want) || !strings.Contains(textBody, want) {
			t.Fatalf("both versions should contain %q", want)
		}
	}
}

func TestProvidersSendExpectedRequests(t *testing.T) {
	var gotPath, gotAuth, gotBody string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		gotPath, gotBody = r.URL.Path, string(b)
		gotAuth = r.Header.Get("Authorization") + r.Header.Get("api-key")
		w.WriteHeader(http.StatusCreated)
	}))
	defer srv.Close()
	oldResend, oldBrevo := resendURL, brevoURL
	resendURL, brevoURL = srv.URL+"/emails", srv.URL+"/v3/smtp/email"
	defer func() { resendURL, brevoURL = oldResend, oldBrevo }()

	e := newEnv(t)
	e.s.cfg.mailFrom, e.s.cfg.mailFromName, e.s.cfg.replyTo = "hello@example.com", "Gaali Finder", "me@example.com"
	e.s.cfg.resendKey, e.s.cfg.brevoKey = "rk", "bk"

	field := func(path ...string) any {
		var m map[string]any
		if err := json.Unmarshal([]byte(gotBody), &m); err != nil {
			t.Fatalf("body is not JSON: %v", err)
		}
		var cur any = m
		for _, k := range path {
			cur = cur.(map[string]any)[k]
		}
		return cur
	}
	if err := e.s.resendSend("to@example.com", "Sub", "<p>h</p>", "t"); err != nil {
		t.Fatal(err)
	}
	if gotPath != "/emails" || gotAuth != "Bearer rk" ||
		field("from") != "Gaali Finder <hello@example.com>" || field("reply_to") != "me@example.com" {
		t.Fatalf("resend request wrong: %s %s %s", gotPath, gotAuth, gotBody)
	}
	if err := e.s.brevoSend("to@example.com", "Sub", "<p>h</p>", "t"); err != nil {
		t.Fatal(err)
	}
	if gotPath != "/v3/smtp/email" || gotAuth != "bk" || field("replyTo", "email") != "me@example.com" {
		t.Fatalf("brevo request wrong: %s %s %s", gotPath, gotAuth, gotBody)
	}
}

func TestResendFailureFallsBackToBrevo(t *testing.T) {
	calls := map[string]int{}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls[r.URL.Path]++
		if r.URL.Path == "/emails" {
			w.WriteHeader(http.StatusForbidden) // e.g. domain not verified yet
			return
		}
		w.WriteHeader(http.StatusCreated)
	}))
	defer srv.Close()
	oldResend, oldBrevo := resendURL, brevoURL
	resendURL, brevoURL = srv.URL+"/emails", srv.URL+"/v3/smtp/email"
	defer func() { resendURL, brevoURL = oldResend, oldBrevo }()

	e := newEnv(t)
	e.s.cfg.resendKey, e.s.cfg.brevoKey = "rk", "bk"
	if err := e.s.resendThenBrevo("to@example.com", "S", "<p>h</p>", "t"); err != nil {
		t.Fatalf("the Brevo fallback should have delivered it: %v", err)
	}
	if calls["/emails"] != 1 || calls["/v3/smtp/email"] != 1 {
		t.Fatalf("expected one Resend attempt then one Brevo send, got %v", calls)
	}
}

func TestSecurityHeaders(t *testing.T) {
	e := newEnv(t)
	for _, path := range []string{"/healthz", "/privacy", "/v1/clips", "/v1/me"} { // includes an error response
		resp, err := http.Get(e.ts.URL + path)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		for header, want := range map[string]string{
			"X-Content-Type-Options":    "nosniff",
			"X-Frame-Options":           "DENY",
			"Referrer-Policy":           "no-referrer",
			"Strict-Transport-Security": "max-age=31536000",
		} {
			if got := resp.Header.Get(header); got != want {
				t.Fatalf("%s %s = %q, want %q", path, header, got, want)
			}
		}
		if !strings.Contains(resp.Header.Get("Content-Security-Policy"), "frame-ancestors 'none'") {
			t.Fatalf("%s is missing its Content-Security-Policy", path)
		}
	}
}
