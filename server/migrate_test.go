package main

import (
	"database/sql"
	"path/filepath"
	"strings"
	"testing"
)

// The users table once had an extra UNIQUE column, which SQLite cannot drop in place. Migration
// 002 rebuilds the table; this checks it on the old layout with linked rows, because a careless
// rebuild would cascade-delete every clip, reaction and session that points at a user.
func TestRebuildUsersKeepsDataAndForeignKeys(t *testing.T) {
	path := filepath.Join(t.TempDir(), "gaali.db")

	old, err := sql.Open("sqlite", "file:"+filepath.ToSlash(path)+"?_pragma=foreign_keys(1)")
	if err != nil {
		t.Fatal(err)
	}
	old.SetMaxOpenConns(1)
	base, _ := migrationFS.ReadFile("migrations/001_init.sql")
	// Add the extra UNIQUE column at the end of the users table definition.
	start := strings.Index(string(base), "CREATE TABLE users (")
	end := start + strings.Index(string(base)[start:], "\n) STRICT;")
	legacy := string(base)[:end] + ",\n  old_backend_id TEXT UNIQUE" + string(base)[end:]
	if start < 0 || end <= start {
		t.Fatal("could not find the users table to extend")
	}
	for _, q := range []string{
		legacy,
		`CREATE TABLE schema_migrations (name TEXT PRIMARY KEY)`,
		`INSERT INTO schema_migrations VALUES ('001_init.sql')`,
		`INSERT INTO users (id, email, email_verified_at, display_name, old_backend_id, created_at) VALUES ('u1','a@x.co',5,'A','legacy-1',1), ('u2','b@x.co',NULL,'B',NULL,2)`,
		`INSERT INTO user_plans (user_id, is_premium, storage_limit_bytes, updated_at) VALUES ('u1',1,0,3)`,
		`INSERT INTO clips (id, uploader_id, uploader_name, file_name, storage_key, mime_type, size_bytes, sha256, created_at) VALUES ('c1','u1','A','f.mp3','k1','audio/mpeg',10,x'00',4)`,
		`INSERT INTO reactions (clip_id, user_id, kind, updated_at) VALUES ('c1','u2','like',5)`,
		`INSERT INTO refresh_tokens (id, user_id, token_hash, family_id, expires_at) VALUES ('r1','u2',x'01','f',9)`,
	} {
		if _, err := old.Exec(q); err != nil {
			t.Fatalf("setting up the old layout: %v\n%s", err, q)
		}
	}
	old.Close()

	db, err := openDB(path) // applies 002
	if err != nil {
		t.Fatalf("migrating: %v", err)
	}
	defer db.Close()

	count := func(q string) int {
		var n int
		if err := db.QueryRow(q).Scan(&n); err != nil {
			t.Fatalf("%s: %v", q, err)
		}
		return n
	}
	if got := count(`SELECT count(*) FROM users`); got != 2 {
		t.Fatalf("users: %d", got)
	}
	for table, want := range map[string]int{"user_plans": 1, "clips": 1, "reactions": 1, "refresh_tokens": 1} {
		if got := count(`SELECT count(*) FROM ` + table); got != want {
			t.Fatalf("%s lost rows in the rebuild: have %d, want %d", table, got, want)
		}
	}
	if count(`SELECT count(*) FROM pragma_table_info('users') WHERE name = 'old_backend_id'`) != 0 {
		t.Fatal("the extra column should be gone")
	}
	if count(`SELECT count(*) FROM users WHERE id = 'u1' AND email = 'a@x.co' AND email_verified_at = 5`) != 1 {
		t.Fatal("user fields changed")
	}
	if count(`SELECT count(*) FROM pragma_foreign_key_check`) != 0 {
		t.Fatal("foreign keys are broken after the rebuild")
	}
	if count(`PRAGMA foreign_keys`) != 1 {
		t.Fatal("foreign key enforcement must be back on after the migration")
	}

	// Enforcement works against the rebuilt table: deleting a user still cascades.
	if _, err := db.Exec(`DELETE FROM users WHERE id = 'u1'`); err != nil {
		t.Fatal(err)
	}
	if count(`SELECT count(*) FROM clips`) != 0 || count(`SELECT count(*) FROM reactions`) != 0 {
		t.Fatal("deleting a user should cascade to their clips and reactions")
	}
	if _, err := db.Exec(`INSERT INTO clips (id, uploader_id, uploader_name, file_name, storage_key, mime_type, size_bytes, sha256, created_at) VALUES ('c2','nobody','n','f.mp3','k2','audio/mpeg',1,x'00',1)`); err == nil {
		t.Fatal("a clip for a user that does not exist must be rejected")
	}
}
