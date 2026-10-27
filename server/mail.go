package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"html"
	"io"
	"log"
	"net/http"
	"time"
)

func logf(format string, args ...any) { log.Printf(format, args...) }

var mailClient = &http.Client{Timeout: 10 * time.Second}

// Provider endpoints are variables so tests can point them at a local server.
var (
	brevoURL  = "https://api.brevo.com/v3/smtp/email"
	resendURL = "https://api.resend.com/emails"
)

func (s *server) fromAddress() string {
	return fmt.Sprintf("%s <%s>", s.cfg.mailFromName, s.cfg.mailFrom)
}

// sendJSON posts a JSON body to a mail provider and turns any non-2xx into an error.
func sendJSON(provider, url string, header map[string]string, payload any) error {
	body, _ := json.Marshal(payload)
	req, err := http.NewRequest(http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")
	for k, v := range header {
		req.Header.Set(k, v)
	}
	resp, err := mailClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
		return fmt.Errorf("%s %d: %s", provider, resp.StatusCode, b)
	}
	return nil
}

// resendSend sends one transactional mail through Resend. Resend leaves links untouched (no click
// tracking unless a domain opts in), which keeps the verification link on our own domain.
func (s *server) resendSend(to, subject, htmlBody, textBody string) error {
	payload := map[string]any{
		"from":    s.fromAddress(),
		"to":      []string{to},
		"subject": subject,
		"html":    htmlBody,
		"text":    textBody,
	}
	if s.cfg.replyTo != "" {
		payload["reply_to"] = s.cfg.replyTo
	}
	return sendJSON("resend", resendURL, map[string]string{"Authorization": "Bearer " + s.cfg.resendKey}, payload)
}

// resendThenBrevo prefers Resend and falls back to Brevo if Resend refuses (for example while its
// sending domain is still unverified), so a provider hiccup never costs a sign-up its mail.
func (s *server) resendThenBrevo(to, subject, htmlBody, textBody string) error {
	err := s.resendSend(to, subject, htmlBody, textBody)
	if err == nil {
		return nil
	}
	logf("resend failed, falling back to brevo: %v", err)
	return s.brevoSend(to, subject, htmlBody, textBody)
}

// brevoSend sends one transactional mail through Brevo's REST API. Brevo rewrites every link
// through its shared tracking domain on the free plan, which hurts inbox placement.
func (s *server) brevoSend(to, subject, htmlBody, textBody string) error {
	payload := map[string]any{
		"sender":      map[string]string{"email": s.cfg.mailFrom, "name": s.cfg.mailFromName},
		"to":          []map[string]string{{"email": to}},
		"subject":     subject,
		"htmlContent": htmlBody,
		"textContent": textBody,
	}
	if s.cfg.replyTo != "" {
		payload["replyTo"] = map[string]string{"email": s.cfg.replyTo}
	}
	return sendJSON("brevo", brevoURL, map[string]string{"api-key": s.cfg.brevoKey}, payload)
}

// mailPage is a complete, table-based HTML document: a real <title>, a hidden preheader, the
// action link as a button plus the address as plain text (not a link: a provider that rewrites
// links would make the visible address differ from where it points, which looks like phishing)
// and a footer that says who sent it and why. Mostly
// text and first-party links reads as a normal transactional mail rather than a stub.
const mailPage = `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>%[1]s</title></head>
<body style="margin:0;padding:0;background:#f4f4f6">
<div style="display:none;max-height:0;overflow:hidden;opacity:0;color:#f4f4f6">%[2]s</div>
<table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background:#f4f4f6"><tr><td align="center" style="padding:24px 12px">
<table role="presentation" width="480" cellpadding="0" cellspacing="0" style="max-width:480px;width:100%%;background:#ffffff;border-radius:8px;font-family:Arial,Helvetica,sans-serif;color:#1b1b1f">
<tr><td style="padding:28px 28px 4px;font-size:20px;font-weight:bold">Gaali Finder</td></tr>
<tr><td style="padding:8px 28px;font-size:16px;line-height:1.5">
<p style="margin:12px 0">Hi %[3]s,</p>
<p style="margin:12px 0">%[4]s</p>
<p style="margin:24px 0"><a href="%[5]s" style="display:inline-block;padding:12px 22px;background:#1a73e8;color:#ffffff;border-radius:6px;text-decoration:none;font-weight:bold">%[6]s</a></p>
<p style="margin:12px 0;font-size:14px;color:#555555">If the button doesn't work, copy and paste this address into your browser:<br><span style="color:#1a73e8;word-break:break-all">%[5]s</span></p>
<p style="margin:12px 0;font-size:14px;color:#555555">%[7]s</p>
</td></tr>
<tr><td style="padding:16px 28px 24px;font-size:12px;line-height:1.5;color:#777777;border-top:1px solid #eeeeee">You are receiving this because a request was made for %[8]s on Gaali Finder, a soundboard app. <a href="%[9]s/privacy" style="color:#777777">Privacy</a> &middot; <a href="%[9]s/terms" style="color:#777777">Terms</a></td></tr>
</table></td></tr></table></body></html>`

// renderMail builds the HTML and plain-text bodies for one action mail.
func renderMail(subject, preheader, name, email, intro, button, link, expiry, base string) (htmlBody, textBody string) {
	e := html.EscapeString
	htmlBody = fmt.Sprintf(mailPage, e(subject), e(preheader), e(name), e(intro), e(link), e(button), e(expiry), e(email), e(base))
	textBody = fmt.Sprintf("Hi %s,\n\n%s\n\n%s\n\n%s\n\n--\nYou are receiving this because a request was made for %s on Gaali Finder, a soundboard app.\nPrivacy: %s/privacy  Terms: %s/terms\n",
		name, intro, link, expiry, email, base, base)
	return
}

func verifyMail(name, email, link, base string) (subject, htmlBody, textBody string) {
	subject = "Verify your email for Gaali Finder"
	htmlBody, textBody = renderMail(subject, "Confirm your email address to finish setting up your Gaali Finder account.",
		name, email, "Please confirm your email address so you can upload clips to Gaali Finder.",
		"Verify email", link, "This link works for 24 hours. If you didn't sign up, you can ignore this mail.", base)
	return
}

func resetMail(name, email, link, base string) (subject, htmlBody, textBody string) {
	subject = "Reset your Gaali Finder password"
	htmlBody, textBody = renderMail(subject, "Choose a new password for your Gaali Finder account.",
		name, email, "We received a request to reset your Gaali Finder password. Use the button below to choose a new one.",
		"Reset password", link, "This link works for 1 hour. If you didn't ask for this, you can ignore this mail and your password will stay the same.", base)
	return
}

// page renders the tiny HTML pages the e-mailed links land on.
func page(w http.ResponseWriter, status int, title, body string) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Referrer-Policy", "no-referrer")
	w.WriteHeader(status)
	fmt.Fprintf(w, `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>%s</title><style>body{font-family:sans-serif;max-width:420px;margin:15vh auto;padding:0 16px;line-height:1.5}
input,button{display:block;width:100%%;box-sizing:border-box;padding:12px;margin:8px 0;font-size:16px}</style>
<h2>%s</h2><div>%s</div>`, html.EscapeString(title), html.EscapeString(title), bodyHTML(body))
}

// bodyHTML passes markup through only when it starts with a tag; plain sentences are escaped.
func bodyHTML(s string) string {
	if len(s) > 0 && s[0] == '<' {
		return s
	}
	return "<p>" + html.EscapeString(s) + "</p>"
}
