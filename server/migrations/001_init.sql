-- Timestamps are unix milliseconds. Ids are random hex strings.

CREATE TABLE users (
  id                 TEXT PRIMARY KEY,
  email              TEXT NOT NULL UNIQUE COLLATE NOCASE,
  email_verified_at  INTEGER,
  password_hash      TEXT,                       -- NULL for Google-only accounts
  google_sub         TEXT UNIQUE,
  display_name       TEXT NOT NULL,
  created_at         INTEGER NOT NULL
) STRICT;

CREATE TABLE user_plans (
  user_id             TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  is_premium          INTEGER NOT NULL DEFAULT 0 CHECK (is_premium IN (0, 1)),
  storage_limit_bytes INTEGER NOT NULL DEFAULT 0, -- 0 = server default for premium
  updated_at          INTEGER NOT NULL
) STRICT;

CREATE TABLE clips (
  id            TEXT PRIMARY KEY,
  uploader_id   TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  uploader_name TEXT NOT NULL,
  file_name     TEXT NOT NULL,
  storage_key   TEXT NOT NULL UNIQUE,
  mime_type     TEXT NOT NULL,
  size_bytes    INTEGER NOT NULL CHECK (size_bytes BETWEEN 1 AND 204800),
  sha256        BLOB NOT NULL,
  like_count    INTEGER NOT NULL DEFAULT 0,
  dislike_count INTEGER NOT NULL DEFAULT 0,
  created_at    INTEGER NOT NULL
) STRICT;

CREATE INDEX clips_created_idx  ON clips (created_at DESC, id DESC);
CREATE INDEX clips_uploader_idx ON clips (uploader_id);

CREATE TABLE reactions (
  clip_id    TEXT NOT NULL REFERENCES clips(id) ON DELETE CASCADE,
  user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  kind       TEXT NOT NULL CHECK (kind IN ('like', 'dislike')),
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (clip_id, user_id)
) STRICT;

-- Counts are recomputed inside the same write transaction as the vote, so none can be lost.
CREATE TRIGGER reactions_ai AFTER INSERT ON reactions BEGIN
  UPDATE clips SET
    like_count    = (SELECT count(*) FROM reactions WHERE clip_id = NEW.clip_id AND kind = 'like'),
    dislike_count = (SELECT count(*) FROM reactions WHERE clip_id = NEW.clip_id AND kind = 'dislike')
  WHERE id = NEW.clip_id;
END;

CREATE TRIGGER reactions_au AFTER UPDATE ON reactions BEGIN
  UPDATE clips SET
    like_count    = (SELECT count(*) FROM reactions WHERE clip_id = NEW.clip_id AND kind = 'like'),
    dislike_count = (SELECT count(*) FROM reactions WHERE clip_id = NEW.clip_id AND kind = 'dislike')
  WHERE id = NEW.clip_id;
END;

CREATE TRIGGER reactions_ad AFTER DELETE ON reactions BEGIN
  UPDATE clips SET
    like_count    = (SELECT count(*) FROM reactions WHERE clip_id = OLD.clip_id AND kind = 'like'),
    dislike_count = (SELECT count(*) FROM reactions WHERE clip_id = OLD.clip_id AND kind = 'dislike')
  WHERE id = OLD.clip_id;
END;

CREATE TABLE refresh_tokens (
  id         TEXT PRIMARY KEY,
  user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash BLOB NOT NULL UNIQUE,
  family_id  TEXT NOT NULL,
  expires_at INTEGER NOT NULL,
  revoked_at INTEGER
) STRICT;

CREATE INDEX refresh_tokens_user_idx   ON refresh_tokens (user_id);
CREATE INDEX refresh_tokens_family_idx ON refresh_tokens (family_id);

CREATE TABLE email_tokens (
  id         TEXT PRIMARY KEY,
  user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  purpose    TEXT NOT NULL CHECK (purpose IN ('verify', 'reset')),
  token_hash BLOB NOT NULL UNIQUE,
  expires_at INTEGER NOT NULL,
  used_at    INTEGER
) STRICT;

CREATE INDEX email_tokens_user_idx ON email_tokens (user_id, purpose);
