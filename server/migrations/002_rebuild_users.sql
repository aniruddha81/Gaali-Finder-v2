-- migrate:foreign-keys-off
-- Rebuilds `users` without the retired old-backend id column (SQLite cannot drop a UNIQUE
-- column in place). Copies only the columns that remain, so it also works on a database that
-- never had the extra column. Other tables keep pointing at users(id) by name.
CREATE TABLE users_new (
  id                 TEXT PRIMARY KEY,
  email              TEXT NOT NULL UNIQUE COLLATE NOCASE,
  email_verified_at  INTEGER,
  password_hash      TEXT,                       -- NULL for Google-only accounts
  google_sub         TEXT UNIQUE,
  display_name       TEXT NOT NULL,
  created_at         INTEGER NOT NULL
) STRICT;

INSERT INTO users_new (id, email, email_verified_at, password_hash, google_sub, display_name, created_at)
  SELECT id, email, email_verified_at, password_hash, google_sub, display_name, created_at FROM users;

DROP TABLE users;
ALTER TABLE users_new RENAME TO users;
