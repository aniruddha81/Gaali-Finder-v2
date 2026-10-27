package main

import (
	"embed"
	"net/http"
)

//go:embed web/*.html web/*.js web/logo.png
var webFS embed.FS

// staticPage serves one embedded HTML page: the public home, privacy and terms pages that
// Google's OAuth consent screen links to.
func staticPage(name string) http.HandlerFunc {
	body, err := webFS.ReadFile("web/" + name)
	if err != nil {
		panic(err) // embedded at build time, so a missing file is a build bug
	}
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Cache-Control", "public, max-age=3600")
		w.Write(body)
	}
}

// logo serves the app icon. It is public and carries no data, so any origin may fetch it; the
// Google Cloud console does so to attach it to the OAuth consent screen.
func logo() http.HandlerFunc {
	body, err := webFS.ReadFile("web/logo.png")
	if err != nil {
		panic(err)
	}
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "image/png")
		w.Header().Set("Cache-Control", "public, max-age=86400")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Write(body)
	}
}
