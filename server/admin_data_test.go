package main

import (
	"net/http"
	"os"
	"path/filepath"
	"testing"
)

// adminEnv returns a test server with a verified admin and a verified regular user who has
// uploaded one clip.
func adminEnv(t *testing.T) (e *env, admin, userTok, userID string, clip map[string]any) {
	e = newEnv(t)
	e.s.cfg.adminEmails = parseEmails("boss@example.com")
	u := e.register("user@example.com")
	userTok, userID = access(u), u["user"].(map[string]any)["id"].(string)
	e.verify(e.lastLink())
	admin = access(e.register("boss@example.com"))
	e.verify(e.lastLink())
	st, clip := e.upload(userTok, "hello.mp3", 100)
	if st != http.StatusCreated && st != http.StatusOK {
		t.Fatalf("upload: %d %v", st, clip)
	}
	return
}

func tableRows(t *testing.T, e *env, admin, query string) map[string]any {
	t.Helper()
	st, m := e.call("GET", "/v1/admin/tables/"+query, admin, nil)
	if st != http.StatusOK {
		t.Fatalf("GET %s: %d %v", query, st, m)
	}
	return m
}

func TestAdminBrowseAndMask(t *testing.T) {
	e, admin, _, _, _ := adminEnv(t)

	_, m := e.call("GET", "/v1/admin/tables", admin, nil)
	if n := len(m["tables"].([]any)); n < 7 {
		t.Fatalf("want every table listed, got %d", n)
	}

	m = tableRows(t, e, admin, "users?q=user@example")
	if m["total"].(float64) != 1 {
		t.Fatalf("search: want 1 user, got %v", m["total"])
	}
	cols := m["table"].(map[string]any)["columns"].([]any)
	row := m["rows"].([]any)[0].([]any)
	for i, c := range cols {
		if c.(map[string]any)["name"] == "password_hash" && row[i] != "(set)" {
			t.Fatalf("password hash must be masked, got %v", row[i])
		}
	}
	if st, _ := e.call("GET", "/v1/admin/tables/nope", admin, nil); st != http.StatusNotFound {
		t.Fatalf("unknown table: want 404, got %d", st)
	}
	if st, _ := e.call("GET", `/v1/admin/tables/users;DROP`, admin, nil); st != http.StatusNotFound {
		t.Fatalf("odd table name: want 404, got %d", st)
	}
}

func TestAdminEditAndRename(t *testing.T) {
	e, admin, _, uid, clip := adminEnv(t)

	st, m := e.call("POST", "/v1/admin/tables/users/update", admin, rowReq{
		Key: map[string]any{"id": uid}, Values: map[string]any{"display_name": "  Renamed  "}})
	if st != http.StatusOK {
		t.Fatalf("rename: %d %v", st, m)
	}
	var name, uploader string
	e.s.db.QueryRow(`SELECT display_name FROM users WHERE id = ?`, uid).Scan(&name)
	e.s.db.QueryRow(`SELECT uploader_name FROM clips WHERE id = ?`, clip["id"]).Scan(&uploader)
	if name != "Renamed" || uploader != "Renamed" {
		t.Fatalf("rename must reach clips: user %q, clip %q", name, uploader)
	}

	st, m = e.call("POST", "/v1/admin/tables/clips/update", admin, rowReq{
		Key: map[string]any{"id": clip["id"]}, Values: map[string]any{"file_name": "new.mp3"}})
	if st != http.StatusOK {
		t.Fatalf("clip rename: %d %v", st, m)
	}

	for _, bad := range []map[string]any{
		{"password_hash": "x"}, {"id": "other"}, {"email": "not an email"}, {"nope": 1}, {"display_name": nil},
	} {
		if st, _ := e.call("POST", "/v1/admin/tables/users/update", admin, rowReq{Key: map[string]any{"id": uid}, Values: bad}); st != http.StatusBadRequest {
			t.Fatalf("%v: want 400, got %d", bad, st)
		}
	}
	if st, _ := e.call("POST", "/v1/admin/tables/clips/update", admin, rowReq{
		Key: map[string]any{"id": clip["id"]}, Values: map[string]any{"storage_key": "x"}}); st != http.StatusBadRequest {
		t.Fatalf("storage_key must be locked, got %d", st)
	}
	if st, _ := e.call("POST", "/v1/admin/tables/users/update", admin, rowReq{
		Key: map[string]any{"id": "missing"}, Values: map[string]any{"display_name": "x"}}); st != http.StatusNotFound {
		t.Fatalf("missing row: want 404, got %d", st)
	}
	if st, _ := e.call("POST", "/v1/admin/tables/admin_audit/delete", admin, rowReq{Key: map[string]any{"id": "x"}}); st != http.StatusForbidden {
		t.Fatalf("audit log is read-only, got %d", st)
	}

	var n int
	e.s.db.QueryRow(`SELECT count(*) FROM admin_audit WHERE action = 'update'`).Scan(&n)
	if n != 2 {
		t.Fatalf("want 2 audited updates, got %d", n)
	}
}

func TestAdminDeleteRemovesFiles(t *testing.T) {
	e, admin, userTok, uid, clip := adminEnv(t)
	file := filepath.Join(e.s.audioDir, clip["fileId"].(string))
	if _, err := os.Stat(file); err != nil {
		t.Fatalf("clip file should exist: %v", err)
	}

	if st, m := e.call("POST", "/v1/admin/tables/clips/delete", admin, rowReq{Key: map[string]any{"id": clip["id"]}}); st != http.StatusOK {
		t.Fatalf("delete clip: %d %v", st, m)
	}
	if _, err := os.Stat(file); !os.IsNotExist(err) {
		t.Fatalf("clip file should be gone, stat err=%v", err)
	}

	// Deleting a user takes their clips and files with them.
	_, c2 := e.upload(userTok, "two.mp3", 50)
	file2 := filepath.Join(e.s.audioDir, c2["fileId"].(string))
	if st, m := e.call("POST", "/v1/admin/tables/users/delete", admin, rowReq{Key: map[string]any{"id": uid}}); st != http.StatusOK {
		t.Fatalf("delete user: %d %v", st, m)
	}
	if _, err := os.Stat(file2); !os.IsNotExist(err) {
		t.Fatalf("user's file should be gone, stat err=%v", err)
	}
	var clips int
	e.s.db.QueryRow(`SELECT count(*) FROM clips`).Scan(&clips)
	if clips != 0 {
		t.Fatalf("clips should cascade, %d left", clips)
	}
}

func TestAdminInsertPlanAndPassword(t *testing.T) {
	e, admin, _, uid, _ := adminEnv(t)

	if st, m := e.call("POST", "/v1/admin/tables/user_plans/insert", admin, rowReq{
		Values: map[string]any{"user_id": uid, "is_premium": 1, "storage_limit_bytes": 5000}}); st != http.StatusOK {
		t.Fatalf("insert plan: %d %v", st, m)
	}
	var premium int
	e.s.db.QueryRow(`SELECT is_premium FROM user_plans WHERE user_id = ?`, uid).Scan(&premium)
	if premium != 1 {
		t.Fatal("plan row not created")
	}
	if st, _ := e.call("POST", "/v1/admin/tables/clips/insert", admin, rowReq{Values: map[string]any{"file_name": "x"}}); st != http.StatusBadRequest {
		t.Fatalf("clips cannot be inserted by hand, got %d", st)
	}

	if st, _ := e.call("POST", "/v1/admin/users/"+uid+"/password", admin, map[string]string{"password": "short"}); st != http.StatusBadRequest {
		t.Fatalf("weak password: want 400, got %d", st)
	}
	if st, m := e.call("POST", "/v1/admin/users/"+uid+"/password", admin, map[string]string{"password": "brand new password"}); st != http.StatusOK {
		t.Fatalf("set password: %d %v", st, m)
	}
	if st, _ := e.call("POST", "/v1/auth/login", "", map[string]string{"email": "user@example.com", "password": "brand new password"}); st != http.StatusOK {
		t.Fatalf("login with the new password: got %d", st)
	}
	if st, _ := e.call("POST", "/v1/admin/users/nobody/revoke", admin, nil); st != http.StatusNotFound {
		t.Fatalf("revoke unknown user: want 404, got %d", st)
	}
}

func TestAdminSQLConsole(t *testing.T) {
	e, admin, _, uid, _ := adminEnv(t)
	run := func(sql string, write bool) (int, map[string]any) {
		return e.call("POST", "/v1/admin/sql", admin, map[string]any{"sql": sql, "write": write})
	}
	name := func() (n string) {
		e.s.db.QueryRow(`SELECT display_name FROM users WHERE id = ?`, uid).Scan(&n)
		return
	}
	update := `UPDATE users SET display_name = 'Via SQL' WHERE id = '` + uid + `'`

	st, m := run(update, false)
	if st != http.StatusOK || m["changes"].(float64) != 1 || m["committed"] != false {
		t.Fatalf("dry run: %d %v", st, m)
	}
	if name() == "Via SQL" {
		t.Fatal("a dry run must not keep its changes")
	}
	if st, m = run(update, true); st != http.StatusOK || m["committed"] != true || name() != "Via SQL" {
		t.Fatalf("write: %d %v, name %q", st, m, name())
	}
	st, m = run("SELECT email FROM users ORDER BY email", false)
	if st != http.StatusOK || len(m["rows"].([]any)) != 2 {
		t.Fatalf("select: %d %v", st, m)
	}
	for _, bad := range []string{"COMMIT", "vacuum", "ATTACH DATABASE 'x' AS y", "DELETE FROM admin_audit", "NOT SQL", ""} {
		if st, _ := run(bad, true); st != http.StatusBadRequest {
			t.Fatalf("%q: want 400, got %d", bad, st)
		}
	}
	var n int
	e.s.db.QueryRow(`SELECT count(*) FROM admin_audit WHERE action = 'sql'`).Scan(&n)
	if n != 1 {
		t.Fatalf("want 1 audited SQL write, got %d", n)
	}
}

func TestAdminOrphans(t *testing.T) {
	e, admin, _, _, clip := adminEnv(t)
	stray := filepath.Join(e.s.audioDir, "0123456789abcdef0123456789abcdef.mp3")
	os.WriteFile(stray, []byte("x"), 0o644)

	_, m := e.call("GET", "/v1/admin/orphans", admin, nil)
	if len(m["orphanFiles"].([]any)) != 1 || len(m["missingFiles"].([]any)) != 0 {
		t.Fatalf("scan: %v", m)
	}
	e.call("POST", "/v1/admin/orphans", admin, nil)
	if _, err := os.Stat(stray); !os.IsNotExist(err) {
		t.Fatal("orphan should be removed")
	}
	if _, err := os.Stat(filepath.Join(e.s.audioDir, clip["fileId"].(string))); err != nil {
		t.Fatal("a referenced file must be kept")
	}
}

func TestAdminDataNeedsAdmin(t *testing.T) {
	e, _, userTok, uid, _ := adminEnv(t)
	for _, c := range [][2]string{
		{"GET", "/v1/admin/tables"}, {"GET", "/v1/admin/tables/users"}, {"POST", "/v1/admin/tables/users/delete"},
		{"POST", "/v1/admin/sql"}, {"POST", "/v1/admin/users/" + uid + "/password"}, {"GET", "/v1/admin/orphans"},
	} {
		if st, _ := e.call(c[0], c[1], userTok, map[string]any{}); st != http.StatusForbidden {
			t.Fatalf("%s %s as a normal user: want 403, got %d", c[0], c[1], st)
		}
		if st, _ := e.call(c[0], c[1], "", nil); st != http.StatusUnauthorized {
			t.Fatalf("%s %s without a token: want 401, got %d", c[0], c[1], st)
		}
	}
}
