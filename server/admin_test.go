package main

import (
	"net/http"
	"strconv"
	"testing"
)

func TestAdminConsole(t *testing.T) {
	e := newEnv(t)
	e.s.cfg.adminEmails = parseEmails(" Boss@Example.com , ")

	if st, _ := e.call("GET", "/v1/admin/metrics", "", nil); st != http.StatusUnauthorized {
		t.Fatalf("no token: want 401, got %d", st)
	}

	other := access(e.register("other@example.com"))
	e.verify(e.lastLink())
	if st, _ := e.call("GET", "/v1/admin/metrics", other, nil); st != http.StatusForbidden {
		t.Fatalf("non-admin: want 403, got %d", st)
	}

	boss := access(e.register("boss@example.com"))
	if st, _ := e.call("GET", "/v1/admin/metrics", boss, nil); st != http.StatusForbidden {
		t.Fatalf("unverified admin e-mail: want 403, got %d", st)
	}
	e.verify(e.lastLink())

	e.call("GET", "/healthz", "", nil)
	e.call("GET", "/v1/me", "", nil) // 401, still counted
	e.s.takeSample()

	st, m := e.call("GET", "/v1/admin/metrics", boss, nil)
	if st != http.StatusOK {
		t.Fatalf("admin: want 200, got %d %v", st, m)
	}
	h := m["http"].(map[string]any)
	if h["total"].(float64) < 2 || h["c4"].(float64) < 1 {
		t.Fatalf("requests not counted: %v", h)
	}
	for _, r := range h["recent"].([]any) {
		if p := r.(map[string]any)["path"].(string); len(p) >= 10 && p[:10] == "/v1/admin/" {
			t.Fatalf("console polling must not be counted, saw %s", p)
		}
	}
	users := m["data"].(map[string]any)["users"].(map[string]any)
	if users["total"].(float64) != 2 {
		t.Fatalf("want 2 users, got %v", users["total"])
	}
	if len(m["history"].([]any)) != 1 {
		t.Fatalf("want one sample, got %v", m["history"])
	}

	// since= returns only newer samples.
	last := m["history"].([]any)[0].(map[string]any)["t"].(float64)
	_, m2 := e.call("GET", "/v1/admin/metrics?since="+strconv.FormatInt(int64(last), 10), boss, nil)
	if n := len(m2["history"].([]any)); n != 0 {
		t.Fatalf("since: want no new samples, got %d", n)
	}
}

func TestAdminDisabledByDefault(t *testing.T) {
	e := newEnv(t)
	tok := access(e.register("solo@example.com"))
	e.verify(e.lastLink())
	if st, _ := e.call("GET", "/v1/admin/metrics", tok, nil); st != http.StatusForbidden {
		t.Fatalf("no ADMIN_EMAILS: want 403, got %d", st)
	}
}

func TestAdminPageCSP(t *testing.T) {
	e := newEnv(t)
	for _, p := range []string{"/admin", "/admin.js"} {
		resp, err := http.Get(e.ts.URL + p)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		if resp.StatusCode != 200 || resp.Header.Get("Content-Security-Policy") == "" {
			t.Fatalf("%s: status %d, csp %q", p, resp.StatusCode, resp.Header.Get("Content-Security-Policy"))
		}
	}
}
