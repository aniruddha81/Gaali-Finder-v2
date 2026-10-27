-- Every write made from the admin console: who, what, and what the data looked like before.
CREATE TABLE admin_audit (
  id     TEXT PRIMARY KEY,
  at     INTEGER NOT NULL,
  admin  TEXT NOT NULL,
  action TEXT NOT NULL,
  target TEXT NOT NULL,
  detail TEXT NOT NULL
) STRICT;

CREATE INDEX admin_audit_at_idx ON admin_audit (at DESC);
