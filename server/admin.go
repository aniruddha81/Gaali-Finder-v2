package main

import (
	"context"
	"fmt"
	"io"
	"io/fs"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"
)

// The admin console: live server and database numbers for the owner only. Everything is kept in
// memory (one hour of history, the last requests and log lines), so it costs nothing at rest and
// starts empty after a restart. ponytail: no persistence; add a metrics table if you want days.

const (
	sampleEvery = 5 * time.Second
	histCap     = 720 // one hour at sampleEvery
	recentCap   = 150
	logCap      = 300
	heavyTTL    = 10 * time.Second
)

type sample struct {
	T     int64   `json:"t"` // unix ms
	Req   int     `json:"req"`
	Err   int     `json:"err"` // 5xx in the interval
	AvgMs float64 `json:"avgMs"`
	CPU   float64 `json:"cpu"` // host, percent; 0 when unknown
	Mem   float64 `json:"mem"` // host, percent used
	Heap  uint64  `json:"heap"`
	Gor   int     `json:"gor"`
	DBMs  float64 `json:"dbMs"` // SELECT 1 round trip; -1 = failed
}

type reqLog struct {
	T      int64   `json:"t"`
	Method string  `json:"method"`
	Path   string  `json:"path"`
	Status int     `json:"status"`
	Ms     float64 `json:"ms"`
	IP     string  `json:"ip"`
}

type routeAgg struct{ count, e4, e5, totalNs, maxNs int64 }

type stats struct {
	start time.Time

	mu      sync.Mutex
	total   int64
	class   [6]int64 // index = status/100
	routes  map[string]*routeAgg
	recent  []reqLog
	logs    []string
	hist    []sample
	reqs    int // since the last sample
	errs    int
	durNs   int64
	cpuIdle uint64
	cpuTot  uint64

	heavyMu sync.Mutex
	heavyAt time.Time
	heavy   map[string]any
}

func newStats() *stats { return &stats{start: time.Now(), routes: map[string]*routeAgg{}} }

func (st *stats) record(pattern, method, path, ip string, status int, d time.Duration) {
	st.mu.Lock()
	defer st.mu.Unlock()
	st.total++
	c := status / 100
	if c < 1 || c > 5 {
		c = 5
	}
	st.class[c]++
	st.reqs++
	st.durNs += d.Nanoseconds()
	if status >= 500 {
		st.errs++
	}
	if pattern == "" {
		pattern = "(unmatched)"
	}
	ra := st.routes[pattern]
	if ra == nil {
		ra = &routeAgg{}
		st.routes[pattern] = ra
	}
	ra.count++
	ra.totalNs += d.Nanoseconds()
	ra.maxNs = max(ra.maxNs, d.Nanoseconds())
	if status >= 500 {
		ra.e5++
	} else if status >= 400 {
		ra.e4++
	}
	if len(path) > 120 {
		path = path[:120]
	}
	st.recent = append(st.recent, reqLog{nowMs(), method, path, status, float64(d.Microseconds()) / 1000, ip})
	if len(st.recent) > recentCap {
		st.recent = st.recent[len(st.recent)-recentCap:]
	}
}

func (st *stats) addLog(line string) {
	st.mu.Lock()
	defer st.mu.Unlock()
	st.logs = append(st.logs, time.Now().Format("15:04:05")+" "+line)
	if len(st.logs) > logCap {
		st.logs = st.logs[len(st.logs)-logCap:]
	}
}

// logTee copies the standard logger's output into the console's ring buffer.
type logTee struct {
	st  *stats
	out io.Writer
}

func (l logTee) Write(p []byte) (int, error) {
	l.st.addLog(strings.TrimRight(string(p), "\n"))
	return l.out.Write(p)
}

type statusRec struct {
	http.ResponseWriter
	status int
}

func (r *statusRec) WriteHeader(code int)        { r.status = code; r.ResponseWriter.WriteHeader(code) }
func (r *statusRec) Unwrap() http.ResponseWriter { return r.ResponseWriter }

// track counts every request except the console's own polling, which would drown real traffic.
func (s *server) track(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/v1/admin/") {
			next.ServeHTTP(w, r)
			return
		}
		rec := &statusRec{ResponseWriter: w, status: http.StatusOK}
		t0 := time.Now()
		next.ServeHTTP(rec, r)
		// r.Pattern is filled in by the mux while it routes, so it is readable here afterwards.
		s.stats.record(r.Pattern, r.Method, r.URL.Path, clientIP(r), rec.status, time.Since(t0))
	})
}

// sampleLoop appends one sample every sampleEvery until ctx ends.
func (s *server) sampleLoop(ctx context.Context) {
	t := time.NewTicker(sampleEvery)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			s.takeSample()
		}
	}
}

func (s *server) takeSample() {
	st := s.stats
	t0 := time.Now()
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	var one int
	err := s.db.QueryRowContext(ctx, `SELECT 1`).Scan(&one)
	cancel()
	dbMs := float64(time.Since(t0).Microseconds()) / 1000
	if err != nil {
		dbMs = -1
	}
	var ms runtime.MemStats
	runtime.ReadMemStats(&ms)
	idle, tot, cpuOK := readCPU()
	memT, memA := readMem()

	st.mu.Lock()
	defer st.mu.Unlock()
	sm := sample{T: nowMs(), Req: st.reqs, Err: st.errs, DBMs: dbMs, Heap: ms.HeapAlloc, Gor: runtime.NumGoroutine()}
	if st.reqs > 0 {
		sm.AvgMs = float64(st.durNs) / float64(st.reqs) / 1e6
	}
	if cpuOK && st.cpuTot > 0 && tot > st.cpuTot {
		sm.CPU = 100 * (1 - float64(idle-st.cpuIdle)/float64(tot-st.cpuTot))
	}
	if cpuOK {
		st.cpuIdle, st.cpuTot = idle, tot
	}
	if memT > 0 {
		sm.Mem = 100 * float64(memT-memA) / float64(memT)
	}
	st.reqs, st.errs, st.durNs = 0, 0, 0
	st.hist = append(st.hist, sm)
	if len(st.hist) > histCap {
		st.hist = st.hist[len(st.hist)-histCap:]
	}
}

// ---- access ----

// adminOnly lets through a valid access token whose account is a verified e-mail listed in
// ADMIN_EMAILS. With that variable empty nobody gets in.
func (s *server) adminOnly(fn func(http.ResponseWriter, *http.Request, userDTO) error) http.HandlerFunc {
	return s.authed(func(w http.ResponseWriter, r *http.Request, uid string) error {
		u, err := loadUser(r.Context(), s.db, uid)
		if err != nil {
			return err
		}
		if !u.EmailVerified || !s.cfg.isAdmin(u.Email) {
			return fail(http.StatusForbidden, "forbidden", "This account is not an admin.")
		}
		return fn(w, r, u)
	})
}

func (c config) isAdmin(email string) bool {
	email = strings.ToLower(strings.TrimSpace(email))
	for _, a := range c.adminEmails {
		if a == email {
			return true
		}
	}
	return false
}

func parseEmails(csv string) []string {
	var out []string
	for _, e := range strings.Split(csv, ",") {
		if e = strings.ToLower(strings.TrimSpace(e)); e != "" {
			out = append(out, e)
		}
	}
	return out
}

// adminAsset serves the console page and script. They hold no data (the API behind them is
// token-gated), but they need a looser CSP than the rest of the site: one same-origin script.
func adminAsset(name, contentType string) http.HandlerFunc {
	body, err := webFS.ReadFile("web/" + name)
	if err != nil {
		panic(err)
	}
	return func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("Content-Type", contentType)
		h.Set("Cache-Control", "no-cache")
		h.Set("X-Robots-Tag", "noindex")
		h.Set("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'")
		w.Write(body)
	}
}

// ---- metrics ----

func (s *server) adminMetrics(w http.ResponseWriter, r *http.Request, _ userDTO) error {
	since, _ := strconv.ParseInt(r.URL.Query().Get("since"), 10, 64)
	heavy, err := s.heavyStats(r.Context())
	if err != nil {
		return err
	}

	var ms runtime.MemStats
	runtime.ReadMemStats(&ms)
	rss, fds := readProc()
	memT, memA := readMem()
	diskT, diskF := diskUsage(s.cfg.dataDir)
	load := readLoad()

	st := s.stats
	st.mu.Lock()
	routes := make([]map[string]any, 0, len(st.routes))
	for name, ra := range st.routes {
		routes = append(routes, map[string]any{
			"route": name, "count": ra.count, "e4": ra.e4, "e5": ra.e5,
			"avgMs": float64(ra.totalNs) / float64(ra.count) / 1e6, "maxMs": float64(ra.maxNs) / 1e6,
		})
	}
	hist := []sample{} // never nil: the console reads history.length
	for _, sm := range st.hist {
		if sm.T > since {
			hist = append(hist, sm)
		}
	}
	recent := append([]reqLog(nil), st.recent...)
	logs := append([]string(nil), st.logs...)
	total, class := st.total, st.class
	st.mu.Unlock()

	s.mailMu.Lock()
	mailToday := s.mailCount
	if s.mailDay != time.Now().UTC().Format("2006-01-02") {
		mailToday = 0
	}
	s.mailMu.Unlock()
	provider := "log only"
	switch {
	case s.cfg.resendKey != "" && s.cfg.brevoKey != "":
		provider = "Resend, Brevo fallback"
	case s.cfg.resendKey != "":
		provider = "Resend"
	case s.cfg.brevoKey != "":
		provider = "Brevo"
	}
	s.lim.mu.Lock()
	limiterKeys := len(s.lim.hits)
	s.lim.mu.Unlock()

	var gcPause float64
	if ms.NumGC > 0 {
		gcPause = float64(ms.PauseNs[(ms.NumGC+255)%256]) / 1e6
	}
	pool := s.db.Stats()
	heavy["pool"] = map[string]any{
		"open": pool.OpenConnections, "inUse": pool.InUse, "idle": pool.Idle,
		"waitCount": pool.WaitCount, "waitMs": pool.WaitDuration.Milliseconds(),
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"now":    nowMs(),
		"uptime": int64(time.Since(st.start).Seconds()),
		"build":  map[string]any{"go": runtime.Version(), "os": runtime.GOOS, "arch": runtime.GOARCH, "cpus": runtime.NumCPU()},
		"process": map[string]any{
			"goroutines": runtime.NumGoroutine(), "heapAlloc": ms.HeapAlloc, "heapSys": ms.HeapSys,
			"stack": ms.StackInuse, "gcCount": ms.NumGC, "gcPauseMs": gcPause, "rss": rss, "fds": fds,
		},
		"host": map[string]any{
			"available": memT > 0, "memTotal": memT, "memUsed": memT - memA,
			"diskTotal": diskT, "diskFree": diskF, "load": load,
		},
		"http": map[string]any{
			"total": total, "c2": class[2], "c3": class[3], "c4": class[4], "c5": class[5],
			"routes": routes, "recent": recent,
		},
		"data":    heavy,
		"mail":    map[string]any{"today": mailToday, "cap": dailyMailCap, "provider": provider},
		"limiter": limiterKeys,
		"history": hist,
		"logs":    logs,
	})
	return nil
}

// heavyStats holds the numbers that need real queries or a directory walk. They are cached for
// heavyTTL so a console polling every two seconds never queues behind them on the single DB
// connection. The returned map is a copy, so callers may add to it.
func (s *server) heavyStats(ctx context.Context) (map[string]any, error) {
	st := s.stats
	st.heavyMu.Lock()
	defer st.heavyMu.Unlock()
	if st.heavy == nil || time.Since(st.heavyAt) > heavyTTL {
		h, err := s.queryHeavy(ctx)
		if err != nil {
			return nil, err
		}
		st.heavy, st.heavyAt = h, time.Now()
	}
	out := make(map[string]any, len(st.heavy)+1)
	for k, v := range st.heavy {
		out[k] = v
	}
	return out, nil
}

func (s *server) queryHeavy(ctx context.Context) (map[string]any, error) {
	var firstErr error
	row := func(query string, dest ...any) {
		if err := s.db.QueryRowContext(ctx, query).Scan(dest...); err != nil && firstErr == nil {
			firstErr = err
		}
	}
	// list runs a query and fully reads it before returning: the pool has one connection, so an
	// open *sql.Rows would block every other query until it is closed.
	list := func(query string, cols int, build func(v []any) map[string]any) []map[string]any {
		out := []map[string]any{}
		rows, err := s.db.QueryContext(ctx, query)
		if err != nil {
			if firstErr == nil {
				firstErr = err
			}
			return out
		}
		defer rows.Close()
		for rows.Next() {
			v := make([]any, cols)
			ptrs := make([]any, cols)
			for i := range v {
				ptrs[i] = &v[i]
			}
			if err := rows.Scan(ptrs...); err != nil {
				if firstErr == nil {
					firstErr = err
				}
				break
			}
			out = append(out, build(v))
		}
		return out
	}

	now := nowMs()
	day, week := now-24*3600_000, now-7*24*3600_000

	var users, verified, google, password, new24, new7 int64
	row(fmt.Sprintf(`SELECT count(*), count(email_verified_at), count(google_sub), count(password_hash),
		coalesce(sum(created_at > %d), 0), coalesce(sum(created_at > %d), 0) FROM users`, day, week),
		&users, &verified, &google, &password, &new24, &new7)
	var premium, sessions, pendingMail int64
	row(`SELECT count(*) FROM user_plans WHERE is_premium = 1`, &premium)
	row(fmt.Sprintf(`SELECT count(*) FROM refresh_tokens WHERE revoked_at IS NULL AND expires_at > %d`, now), &sessions)
	row(fmt.Sprintf(`SELECT count(*) FROM email_tokens WHERE used_at IS NULL AND expires_at > %d`, now), &pendingMail)
	var clips, clipBytes, likes, dislikes, clips24 int64
	row(fmt.Sprintf(`SELECT count(*), coalesce(sum(size_bytes), 0), coalesce(sum(like_count), 0),
		coalesce(sum(dislike_count), 0), coalesce(sum(created_at > %d), 0) FROM clips`, day),
		&clips, &clipBytes, &likes, &dislikes, &clips24)
	var pages, pageSize, free int64
	var journal string
	row(`PRAGMA page_count`, &pages)
	row(`PRAGMA page_size`, &pageSize)
	row(`PRAGMA freelist_count`, &free)
	row(`PRAGMA journal_mode`, &journal)

	var names []string
	for _, m := range list(`SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name`, 1,
		func(v []any) map[string]any { return map[string]any{"name": v[0]} }) {
		names = append(names, m["name"].(string))
	}
	// dbstat may be missing from a build; table sizes are then simply left out.
	sizes := map[string]int64{}
	for _, m := range list(`SELECT name, sum(pgsize) FROM dbstat GROUP BY name`, 2,
		func(v []any) map[string]any { return map[string]any{"name": v[0], "size": v[1]} }) {
		n, _ := m["name"].(string)
		sizes[n], _ = m["size"].(int64)
	}
	if firstErr != nil && strings.Contains(firstErr.Error(), "dbstat") {
		firstErr = nil
	}
	tables := make([]map[string]any, 0, len(names))
	for _, n := range names {
		var rows int64
		row(`SELECT count(*) FROM "`+strings.ReplaceAll(n, `"`, `""`)+`"`, &rows)
		tables = append(tables, map[string]any{"name": n, "rows": rows, "bytes": sizes[n]})
	}

	signups := list(`SELECT display_name, email, created_at, email_verified_at IS NOT NULL, google_sub IS NOT NULL
		FROM users ORDER BY created_at DESC LIMIT 10`, 5, func(v []any) map[string]any {
		return map[string]any{"name": v[0], "email": v[1], "at": v[2], "verified": v[3], "google": v[4]}
	})
	recentClips := list(`SELECT file_name, uploader_name, size_bytes, like_count, dislike_count, created_at
		FROM clips ORDER BY created_at DESC LIMIT 10`, 6, func(v []any) map[string]any {
		return map[string]any{"file": v[0], "by": v[1], "bytes": v[2], "likes": v[3], "dislikes": v[4], "at": v[5]}
	})
	top := list(`SELECT uploader_name, count(*), sum(size_bytes) FROM clips GROUP BY uploader_id
		ORDER BY count(*) DESC LIMIT 5`, 3, func(v []any) map[string]any {
		return map[string]any{"name": v[0], "clips": v[1], "bytes": v[2]}
	})
	if firstErr != nil {
		return nil, firstErr
	}

	var audioFiles, audioBytes int64
	filepath.WalkDir(s.audioDir, func(_ string, d fs.DirEntry, err error) error {
		if err == nil && !d.IsDir() {
			if fi, e := d.Info(); e == nil {
				audioFiles++
				audioBytes += fi.Size()
			}
		}
		return nil
	})
	fileSize := func(suffix string) int64 {
		fi, err := os.Stat(filepath.Join(s.cfg.dataDir, "gaali.db"+suffix))
		if err != nil {
			return 0
		}
		return fi.Size()
	}

	return map[string]any{
		"users": map[string]any{
			"total": users, "verified": verified, "google": google, "password": password,
			"new24h": new24, "new7d": new7, "premium": premium, "sessions": sessions, "pendingMail": pendingMail,
			"recent": signups,
		},
		"clips": map[string]any{
			"total": clips, "bytes": clipBytes, "likes": likes, "dislikes": dislikes, "new24h": clips24,
			"recent": recentClips, "top": top,
		},
		"db": map[string]any{
			"pages": pages, "pageSize": pageSize, "freePages": free, "journal": journal,
			"fileBytes": fileSize(""), "walBytes": fileSize("-wal"), "shmBytes": fileSize("-shm"),
			"tables": tables,
		},
		"audio": map[string]any{"files": audioFiles, "bytes": audioBytes, "expectedFiles": clips, "expectedBytes": clipBytes},
	}, nil
}
