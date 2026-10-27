package main

import (
	"context"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"math"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// Read and write access to every table from the admin console. Rows are addressed by primary key,
// every write runs in one transaction together with its audit row, and the few columns that cannot
// be edited raw (password hashes, storage keys, blobs, primary keys) go through dedicated actions.

const sqlRowCap = 500

// Tables the console only shows: the migration log and its own audit trail.
var readOnlyTables = map[string]bool{"schema_migrations": true, "admin_audit": true}

type colInfo struct {
	Name    string `json:"name"`
	Type    string `json:"type"`
	NotNull bool   `json:"notNull"`
	PK      int    `json:"pk"`
	Locked  bool   `json:"locked"` // cannot be changed by editing a row
}

type tableMeta struct {
	Name      string    `json:"name"`
	Cols      []colInfo `json:"columns"`
	ReadOnly  bool      `json:"readOnly"`
	CanInsert bool      `json:"canInsert"`
}

func qi(ident string) string { return `"` + strings.ReplaceAll(ident, `"`, `""`) + `"` }

func (m *tableMeta) col(name string) (colInfo, bool) {
	for _, c := range m.Cols {
		if c.Name == name {
			return c, true
		}
	}
	return colInfo{}, false
}

func (m *tableMeta) pks() []colInfo {
	var out []colInfo
	for _, c := range m.Cols {
		if c.PK > 0 {
			out = append(out, c)
		}
	}
	return out
}

func loadMeta(ctx context.Context, q dbtx, name string) (*tableMeta, error) {
	var n int
	if err := q.QueryRowContext(ctx,
		`SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = ? AND name NOT LIKE 'sqlite_%'`, name).Scan(&n); err != nil {
		return nil, err
	}
	if n == 0 {
		return nil, fail(http.StatusNotFound, "not_found", "No such table.")
	}
	rows, err := q.QueryContext(ctx, `SELECT name, type, "notnull", pk FROM pragma_table_info(?)`, name)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	m := &tableMeta{Name: name, ReadOnly: readOnlyTables[name], CanInsert: !readOnlyTables[name]}
	for rows.Next() {
		var c colInfo
		var notNull int
		if err := rows.Scan(&c.Name, &c.Type, &notNull, &c.PK); err != nil {
			return nil, err
		}
		c.NotNull = notNull == 1
		c.Type = strings.ToUpper(c.Type)
		c.Locked = c.PK > 0 || c.Type == "BLOB" || (name == "users" && c.Name == "password_hash") || (name == "clips" && c.Name == "storage_key")
		if c.Type == "BLOB" { // a row that needs a hash or key cannot be made by hand
			m.CanInsert = false
		}
		m.Cols = append(m.Cols, c)
	}
	return m, rows.Err()
}

// shown turns a stored value into what the console displays: blobs as hex, password hashes hidden.
func shown(table, col string, v any) any {
	switch x := v.(type) {
	case []byte:
		return hex.EncodeToString(x)
	case string:
		if table == "users" && col == "password_hash" {
			return "(set)"
		}
		return x
	}
	return v
}

func dbFail(err error) error {
	if err != nil && strings.Contains(err.Error(), "constraint failed") {
		return fail(http.StatusBadRequest, "constraint", err.Error())
	}
	return err
}

func audit(ctx context.Context, q dbtx, admin userDTO, action, target string, detail any) error {
	b, _ := json.Marshal(detail)
	d := string(b)
	if len(d) > 4000 {
		d = d[:4000]
	}
	_, err := q.ExecContext(ctx, `INSERT INTO admin_audit (id, at, admin, action, target, detail) VALUES (?, ?, ?, ?, ?, ?)`,
		randHex(16), nowMs(), admin.Email, action, target, d)
	return err
}

// keyText renders a row key for the audit log, e.g. {"id":"ab12"}.
func keyText(key map[string]any) string {
	b, _ := json.Marshal(key)
	return string(b)
}

func invalid(msg string) error { return fail(http.StatusBadRequest, "invalid_request", msg) }

// coerce checks a JSON value against the column's type; null is only allowed on nullable columns.
func coerce(table string, c colInfo, v any) (any, error) {
	if v == nil {
		if c.NotNull {
			return nil, invalid(c.Name + " cannot be empty.")
		}
		return nil, nil
	}
	var out any
	switch c.Type {
	case "INTEGER":
		switch x := v.(type) {
		case float64:
			if x != math.Trunc(x) {
				return nil, invalid(c.Name + " must be a whole number.")
			}
			out = int64(x)
		case string:
			n, err := strconv.ParseInt(strings.TrimSpace(x), 10, 64)
			if err != nil {
				return nil, invalid(c.Name + " must be a whole number.")
			}
			out = n
		case bool:
			if x {
				out = int64(1)
			} else {
				out = int64(0)
			}
		}
	case "TEXT":
		switch x := v.(type) {
		case string:
			out = x
		case float64:
			out = strconv.FormatFloat(x, 'f', -1, 64)
		}
	}
	if out == nil {
		return nil, invalid(c.Name + " cannot be set to that value.")
	}
	if table == "users" && c.Name == "email" {
		e, err := cleanEmail(out.(string))
		if err != nil {
			return nil, err
		}
		out = e
	}
	if table == "users" && c.Name == "display_name" {
		if out = strings.TrimSpace(out.(string)); out == "" {
			return nil, invalid("display_name cannot be empty.")
		}
	}
	return out, nil
}

// keyWhere builds "pk1 = ? AND pk2 = ?" from the key object the client sent back.
func (m *tableMeta) keyWhere(key map[string]any) (string, []any, error) {
	pks := m.pks()
	if len(pks) == 0 || len(key) != len(pks) {
		return "", nil, invalid("Row key does not match the table's primary key.")
	}
	var parts []string
	var args []any
	for _, c := range pks {
		v, ok := key[c.Name]
		if !ok {
			return "", nil, invalid("Row key is missing " + c.Name + ".")
		}
		cv, err := coerce(m.Name, colInfo{Name: c.Name, Type: c.Type, NotNull: true}, v)
		if err != nil {
			return "", nil, err
		}
		parts = append(parts, qi(c.Name)+" = ?")
		args = append(args, cv)
	}
	return strings.Join(parts, " AND "), args, nil
}

// snapshot reads the whole row (display form) so audits can show what was there before.
func snapshot(ctx context.Context, q dbtx, m *tableMeta, where string, args []any) (map[string]any, error) {
	names := make([]string, len(m.Cols))
	for i, c := range m.Cols {
		names[i] = qi(c.Name)
	}
	vals := make([]any, len(m.Cols))
	ptrs := make([]any, len(vals))
	for i := range vals {
		ptrs[i] = &vals[i]
	}
	err := q.QueryRowContext(ctx, `SELECT `+strings.Join(names, ", ")+` FROM `+qi(m.Name)+` WHERE `+where, args...).Scan(ptrs...)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, fail(http.StatusNotFound, "not_found", "That row no longer exists.")
	}
	if err != nil {
		return nil, err
	}
	row := make(map[string]any, len(vals))
	for i, c := range m.Cols {
		row[c.Name] = shown(m.Name, c.Name, vals[i])
	}
	return row, nil
}

func collectStrings(ctx context.Context, q dbtx, query string, args ...any) ([]string, error) {
	rows, err := q.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var s string
		if err := rows.Scan(&s); err != nil {
			return nil, err
		}
		out = append(out, s)
	}
	return out, rows.Err()
}

func (s *server) removeAudio(keys []string) {
	for _, k := range keys {
		if !storageKeyRE.MatchString(k) {
			continue
		}
		if err := os.Remove(filepath.Join(s.audioDir, k)); err != nil && !errors.Is(err, os.ErrNotExist) {
			logf("admin: removing %s: %v", k, err)
		}
	}
}

// ---- reading ----

func (s *server) adminTables(w http.ResponseWriter, r *http.Request, _ userDTO) error {
	names, err := collectStrings(r.Context(), s.db,
		`SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name`)
	if err != nil {
		return err
	}
	out := make([]map[string]any, 0, len(names))
	for _, n := range names {
		m, err := loadMeta(r.Context(), s.db, n)
		if err != nil {
			return err
		}
		var rows int64
		if err := s.db.QueryRowContext(r.Context(), `SELECT count(*) FROM `+qi(n)).Scan(&rows); err != nil {
			return err
		}
		out = append(out, map[string]any{"name": n, "rows": rows, "readOnly": m.ReadOnly, "canInsert": m.CanInsert})
	}
	writeJSON(w, http.StatusOK, map[string]any{"tables": out})
	return nil
}

func (s *server) adminRows(w http.ResponseWriter, r *http.Request, _ userDTO) error {
	ctx := r.Context()
	m, err := loadMeta(ctx, s.db, r.PathValue("table"))
	if err != nil {
		return err
	}
	q := r.URL.Query()
	limit, _ := strconv.Atoi(q.Get("limit"))
	if limit < 1 || limit > 200 {
		limit = 50
	}
	offset, _ := strconv.Atoi(q.Get("offset"))
	offset = max(offset, 0)

	where, args := "", []any{}
	if term := strings.TrimSpace(q.Get("q")); term != "" {
		like := "%" + strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`).Replace(term) + "%"
		var parts []string
		for _, c := range m.Cols {
			if c.Type != "BLOB" && !(m.Name == "users" && c.Name == "password_hash") {
				parts = append(parts, `CAST(`+qi(c.Name)+` AS TEXT) LIKE ? ESCAPE '\'`)
				args = append(args, like)
			}
		}
		where = " WHERE " + strings.Join(parts, " OR ")
	}
	order := "rowid DESC"
	if sc := q.Get("sort"); sc != "" {
		if _, ok := m.col(sc); ok {
			dir := "ASC"
			if q.Get("dir") == "desc" {
				dir = "DESC"
			}
			order = qi(sc) + " " + dir
		}
	} else {
		for _, n := range []string{"created_at", "at", "updated_at"} {
			if _, ok := m.col(n); ok {
				order = qi(n) + " DESC"
				break
			}
		}
	}

	var total int64
	if err := s.db.QueryRowContext(ctx, `SELECT count(*) FROM `+qi(m.Name)+where, args...).Scan(&total); err != nil {
		return err
	}
	names := make([]string, len(m.Cols))
	for i, c := range m.Cols {
		names[i] = qi(c.Name)
	}
	rows, err := s.db.QueryContext(ctx,
		`SELECT `+strings.Join(names, ", ")+` FROM `+qi(m.Name)+where+` ORDER BY `+order+` LIMIT ? OFFSET ?`,
		append(args, limit, offset)...)
	if err != nil {
		return err
	}
	defer rows.Close()
	out := [][]any{}
	for rows.Next() {
		vals := make([]any, len(m.Cols))
		ptrs := make([]any, len(vals))
		for i := range vals {
			ptrs[i] = &vals[i]
		}
		if err := rows.Scan(ptrs...); err != nil {
			return err
		}
		for i, c := range m.Cols {
			vals[i] = shown(m.Name, c.Name, vals[i])
		}
		out = append(out, vals)
	}
	if err := rows.Err(); err != nil {
		return err
	}
	writeJSON(w, http.StatusOK, map[string]any{"table": m, "rows": out, "total": total, "limit": limit, "offset": offset})
	return nil
}

// ---- writing ----

type rowReq struct {
	Key    map[string]any `json:"key"`
	Values map[string]any `json:"values"`
}

func (s *server) adminWrite(kind string) func(http.ResponseWriter, *http.Request, userDTO) error {
	return func(w http.ResponseWriter, r *http.Request, admin userDTO) error {
		var in rowReq
		if err := readJSON(r, &in); err != nil {
			return err
		}
		ctx := r.Context()
		tx, err := s.db.BeginTx(ctx, nil)
		if err != nil {
			return err
		}
		defer tx.Rollback()
		m, err := loadMeta(ctx, tx, r.PathValue("table"))
		if err != nil {
			return err
		}
		if m.ReadOnly {
			return fail(http.StatusForbidden, "read_only", m.Name+" cannot be changed from the console.")
		}

		var files []string
		target := m.Name
		var detail any
		switch kind {
		case "insert":
			if !m.CanInsert {
				return invalid("Rows cannot be added to " + m.Name + " by hand.")
			}
			detail, err = s.insertRow(ctx, tx, m, in.Values)
		case "update":
			target, detail, err = s.updateRow(ctx, tx, m, in)
		case "delete":
			target, detail, files, err = s.deleteRow(ctx, tx, m, in)
		}
		if err != nil {
			return dbFail(err)
		}
		if err := audit(ctx, tx, admin, kind, target, detail); err != nil {
			return err
		}
		if err := tx.Commit(); err != nil {
			return err
		}
		s.removeAudio(files) // after the commit, like deleteClip: a failed unlink only leaves an orphan
		writeJSON(w, http.StatusOK, map[string]any{"ok": true})
		return nil
	}
}

func (s *server) insertRow(ctx context.Context, tx dbtx, m *tableMeta, vals map[string]any) (any, error) {
	given := map[string]any{}
	for name, v := range vals {
		c, ok := m.col(name)
		if !ok {
			return nil, invalid("Unknown column " + name + ".")
		}
		if c.Type == "BLOB" || c.Locked && c.PK == 0 {
			return nil, invalid(name + " cannot be set here.")
		}
		cv, err := coerce(m.Name, c, v)
		if err != nil {
			return nil, err
		}
		given[name] = cv
	}
	for _, c := range m.Cols {
		if _, ok := given[c.Name]; ok {
			continue
		}
		switch {
		case c.Name == "id" && c.PK > 0 && c.Type == "TEXT":
			given[c.Name] = randHex(16)
		case c.Name == "created_at" || c.Name == "updated_at" || c.Name == "at":
			given[c.Name] = nowMs()
		}
	}
	if m.Name == "users" {
		if given["email"] == nil {
			return nil, invalid("email is required.")
		}
		if given["display_name"] == nil {
			given["display_name"], _, _ = strings.Cut(given["email"].(string), "@")
		}
	}
	var cols, marks []string
	var args []any
	for _, c := range m.Cols {
		if v, ok := given[c.Name]; ok {
			cols, marks, args = append(cols, qi(c.Name)), append(marks, "?"), append(args, v)
		}
	}
	if len(cols) == 0 {
		return nil, invalid("No values given.")
	}
	if _, err := tx.ExecContext(ctx, `INSERT INTO `+qi(m.Name)+` (`+strings.Join(cols, ", ")+`) VALUES (`+strings.Join(marks, ", ")+`)`, args...); err != nil {
		return nil, err
	}
	return given, nil
}

func (s *server) updateRow(ctx context.Context, tx dbtx, m *tableMeta, in rowReq) (string, any, error) {
	where, kargs, err := m.keyWhere(in.Key)
	if err != nil {
		return "", nil, err
	}
	if len(in.Values) == 0 {
		return "", nil, invalid("Nothing to change.")
	}
	target := m.Name + " " + keyText(in.Key)
	before, err := snapshot(ctx, tx, m, where, kargs)
	if err != nil {
		return "", nil, err
	}
	var sets []string
	var args []any
	changed := map[string]any{}
	for name, v := range in.Values {
		c, ok := m.col(name)
		if !ok {
			return "", nil, invalid("Unknown column " + name + ".")
		}
		if c.Locked {
			return "", nil, invalid(name + " cannot be edited here.")
		}
		cv, err := coerce(m.Name, c, v)
		if err != nil {
			return "", nil, err
		}
		sets, args = append(sets, qi(name)+" = ?"), append(args, cv)
		changed[name] = cv
	}
	if _, err := tx.ExecContext(ctx, `UPDATE `+qi(m.Name)+` SET `+strings.Join(sets, ", ")+` WHERE `+where, append(args, kargs...)...); err != nil {
		return "", nil, err
	}
	// clips keep a copy of the uploader's name, so a rename has to reach them too.
	if name, ok := changed["display_name"]; ok && m.Name == "users" {
		if _, err := tx.ExecContext(ctx, `UPDATE clips SET uploader_name = ? WHERE uploader_id = ?`, name, in.Key["id"]); err != nil {
			return "", nil, err
		}
	}
	was := map[string]any{}
	for name := range changed {
		was[name] = before[name]
	}
	return target, map[string]any{"before": was, "after": changed}, nil
}

func (s *server) deleteRow(ctx context.Context, tx dbtx, m *tableMeta, in rowReq) (string, any, []string, error) {
	where, args, err := m.keyWhere(in.Key)
	if err != nil {
		return "", nil, nil, err
	}
	before, err := snapshot(ctx, tx, m, where, args)
	if err != nil {
		return "", nil, nil, err
	}
	var files []string
	switch m.Name {
	case "clips":
		files, err = collectStrings(ctx, tx, `SELECT storage_key FROM clips WHERE id = ?`, in.Key["id"])
	case "users": // their clips go with them (ON DELETE CASCADE), and so must their audio files
		files, err = collectStrings(ctx, tx, `SELECT storage_key FROM clips WHERE uploader_id = ?`, in.Key["id"])
	}
	if err != nil {
		return "", nil, nil, err
	}
	if _, err := tx.ExecContext(ctx, `DELETE FROM `+qi(m.Name)+` WHERE `+where, args...); err != nil {
		return "", nil, nil, err
	}
	return m.Name + " " + keyText(in.Key), map[string]any{"deleted": before, "audioFiles": len(files)}, files, nil
}

func (s *server) adminSetPassword(w http.ResponseWriter, r *http.Request, admin userDTO) error {
	var in struct{ Password string }
	if err := readJSON(r, &in); err != nil {
		return err
	}
	if n := len(in.Password); n < 10 || n > 128 {
		return fail(http.StatusBadRequest, "weak_password", "Use a password of 10 to 128 characters.")
	}
	return s.userAction(w, r, admin, "set-password", func(ctx context.Context, tx dbtx, id string) error {
		if _, err := tx.ExecContext(ctx, `UPDATE users SET password_hash = ? WHERE id = ?`, hashPassword(in.Password), id); err != nil {
			return err
		}
		_, err := tx.ExecContext(ctx, `UPDATE refresh_tokens SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL`, nowMs(), id)
		return err
	})
}

func (s *server) adminRevoke(w http.ResponseWriter, r *http.Request, admin userDTO) error {
	return s.userAction(w, r, admin, "revoke-sessions", func(ctx context.Context, tx dbtx, id string) error {
		_, err := tx.ExecContext(ctx, `UPDATE refresh_tokens SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL`, nowMs(), id)
		return err
	})
}

func (s *server) userAction(w http.ResponseWriter, r *http.Request, admin userDTO, action string, fn func(context.Context, dbtx, string) error) error {
	ctx, id := r.Context(), r.PathValue("id")
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var email string
	if err := tx.QueryRowContext(ctx, `SELECT email FROM users WHERE id = ?`, id).Scan(&email); errors.Is(err, sql.ErrNoRows) {
		return fail(http.StatusNotFound, "not_found", "No such user.")
	} else if err != nil {
		return err
	}
	if err := fn(ctx, tx, id); err != nil {
		return err
	}
	if err := audit(ctx, tx, admin, action, "users "+id, map[string]string{"email": email}); err != nil {
		return err
	}
	if err := tx.Commit(); err != nil {
		return err
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
	return nil
}

// ---- orphaned audio ----

// orphans compares the audio folder with the clips table: files nothing points at, and clips
// whose file is gone.
func (s *server) orphans(ctx context.Context) (files, missing []string, err error) {
	keys, err := collectStrings(ctx, s.db, `SELECT storage_key FROM clips`)
	if err != nil {
		return nil, nil, err
	}
	known := map[string]bool{}
	for _, k := range keys {
		known[k] = true
	}
	entries, err := os.ReadDir(s.audioDir)
	if err != nil {
		return nil, nil, err
	}
	onDisk := map[string]bool{}
	for _, e := range entries {
		if !e.IsDir() && storageKeyRE.MatchString(e.Name()) {
			onDisk[e.Name()] = true
			if !known[e.Name()] {
				files = append(files, e.Name())
			}
		}
	}
	for _, k := range keys {
		if !onDisk[k] {
			missing = append(missing, k)
		}
	}
	return files, missing, nil
}

func (s *server) adminOrphans(w http.ResponseWriter, r *http.Request, admin userDTO) error {
	files, missing, err := s.orphans(r.Context())
	if err != nil {
		return err
	}
	if r.Method == http.MethodPost {
		s.removeAudio(files)
		if err := audit(r.Context(), s.db, admin, "remove-orphans", "audio", map[string]any{"files": files}); err != nil {
			return err
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"orphanFiles": nonNil(files), "missingFiles": nonNil(missing), "removed": r.Method == http.MethodPost})
	return nil
}

func nonNil(s []string) []string {
	if s == nil {
		return []string{}
	}
	return s
}

// ---- SQL console ----

// adminSQL runs one statement. Unless write is set it runs inside a transaction that is rolled
// back, so a dry run shows exactly what a write would do without keeping any of it.
func (s *server) adminSQL(w http.ResponseWriter, r *http.Request, admin userDTO) error {
	var in struct {
		SQL   string `json:"sql"`
		Write bool   `json:"write"`
	}
	if err := readJSON(r, &in); err != nil {
		return err
	}
	stmt := strings.TrimSpace(in.SQL)
	if stmt == "" {
		return invalid("Enter a statement.")
	}
	// Statements that would escape the transaction (or the database file) are not run at all.
	words := strings.FieldsFunc(stmt, func(r rune) bool { return r == ' ' || r == '\n' || r == '\t' || r == '\r' || r == '(' })
	if len(words) == 0 {
		return invalid("Enter a statement.")
	}
	first := strings.ToUpper(words[0])
	switch first {
	case "BEGIN", "COMMIT", "END", "ROLLBACK", "SAVEPOINT", "RELEASE", "VACUUM", "ATTACH", "DETACH":
		return invalid(first + " is not allowed in the console.")
	}
	if low := strings.ToLower(stmt); first != "SELECT" && (strings.Contains(low, "admin_audit") || strings.Contains(low, "schema_migrations")) {
		return invalid("The audit log and migration log cannot be changed.")
	}

	ctx, cancel := context.WithTimeout(r.Context(), 10*time.Second)
	defer cancel()
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()

	var before int64
	if err := tx.QueryRowContext(ctx, `SELECT total_changes()`).Scan(&before); err != nil {
		return err
	}
	rows, err := tx.QueryContext(ctx, stmt)
	if err != nil {
		return invalid(err.Error())
	}
	cols, err := rows.Columns()
	if err != nil {
		rows.Close()
		return err
	}
	out := [][]any{}
	truncated := false
	for rows.Next() {
		if len(out) == sqlRowCap {
			truncated = true
			break
		}
		vals := make([]any, len(cols))
		ptrs := make([]any, len(vals))
		for i := range vals {
			ptrs[i] = &vals[i]
		}
		if err := rows.Scan(ptrs...); err != nil {
			rows.Close()
			return invalid(err.Error())
		}
		for i := range vals {
			vals[i] = shown("", "", vals[i])
		}
		out = append(out, vals)
	}
	rerr := rows.Err()
	rows.Close()
	if rerr != nil {
		return invalid(rerr.Error())
	}
	var after int64
	if err := tx.QueryRowContext(ctx, `SELECT total_changes()`).Scan(&after); err != nil {
		return err
	}
	changed := after - before

	committed := false
	if in.Write && changed > 0 {
		if err := audit(ctx, tx, admin, "sql", "sql", map[string]any{"statement": stmt, "changes": changed}); err != nil {
			return err
		}
		if err := tx.Commit(); err != nil {
			return dbFail(err)
		}
		committed = true
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"columns": cols, "rows": out, "truncated": truncated, "changes": changed, "committed": committed,
	})
	return nil
}
