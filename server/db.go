package main

import (
	"context"
	"database/sql"
	"embed"
	"fmt"
	"path/filepath"
	"strings"

	_ "modernc.org/sqlite"
)

//go:embed migrations/*.sql
var migrationFS embed.FS

// dbtx is what *sql.DB and *sql.Tx have in common.
type dbtx interface {
	ExecContext(ctx context.Context, query string, args ...any) (sql.Result, error)
	QueryContext(ctx context.Context, query string, args ...any) (*sql.Rows, error)
	QueryRowContext(ctx context.Context, query string, args ...any) *sql.Row
}

func openDB(path string) (*sql.DB, error) {
	// _txlock=immediate makes every transaction take the write lock up front, which is what
	// serializes the quota check against the insert.
	dsn := "file:" + filepath.ToSlash(path) +
		"?_pragma=foreign_keys(1)&_pragma=journal_mode(WAL)&_pragma=busy_timeout(5000)&_txlock=immediate"
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}
	// ponytail: one connection means no SQLITE_BUSY and no lock juggling; raise it only if reads
	// ever queue behind writes. Never touch s.db while holding a *sql.Tx — it would deadlock.
	db.SetMaxOpenConns(1)
	if err := migrate(db); err != nil {
		db.Close()
		return nil, err
	}
	return db, nil
}

// migrate applies migrations/*.sql in name order, once each.
func migrate(db *sql.DB) error {
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS schema_migrations (name TEXT PRIMARY KEY)`); err != nil {
		return err
	}
	files, err := migrationFS.ReadDir("migrations")
	if err != nil {
		return err
	}
	for _, f := range files {
		var done int
		if err := db.QueryRow(`SELECT count(*) FROM schema_migrations WHERE name = ?`, f.Name()).Scan(&done); err != nil {
			return err
		}
		if done > 0 {
			continue
		}
		script, err := migrationFS.ReadFile("migrations/" + f.Name())
		if err != nil {
			return err
		}
		if err := applyMigration(db, f.Name(), string(script)); err != nil {
			return err
		}
	}
	return nil
}

// fkOffMarker on a migration's first line asks for foreign-key enforcement to be off while it
// runs. SQLite needs that to rebuild a table that others reference: dropping the old table
// would otherwise cascade-delete the rows that point at it.
const fkOffMarker = "-- migrate:foreign-keys-off"

func applyMigration(db *sql.DB, name, script string) error {
	if strings.HasPrefix(script, fkOffMarker) {
		// The pool has a single connection, so the pragma holds for the whole transaction below.
		if _, err := db.Exec(`PRAGMA foreign_keys = OFF`); err != nil {
			return err
		}
		defer db.Exec(`PRAGMA foreign_keys = ON`)
	}
	tx, err := db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err := tx.Exec(script); err != nil {
		return fmt.Errorf("migration %s: %w", name, err)
	}
	if strings.HasPrefix(script, fkOffMarker) {
		var broken int
		if err := tx.QueryRow(`SELECT count(*) FROM pragma_foreign_key_check`).Scan(&broken); err != nil {
			return err
		}
		if broken > 0 {
			return fmt.Errorf("migration %s left %d broken foreign keys; rolled back", name, broken)
		}
	}
	if _, err := tx.Exec(`INSERT INTO schema_migrations (name) VALUES (?)`, name); err != nil {
		return err
	}
	return tx.Commit()
}
