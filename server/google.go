package main

import (
	"context"
	"crypto/rsa"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"math/big"
	"net/http"
	"sync"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

const googleCertsURL = "https://www.googleapis.com/oauth2/v3/certs"

type googleClaims struct {
	Sub, Email, Name string
}

// googleVerifier checks Google ID tokens against Google's published signing keys.
type googleVerifier struct {
	clientID string

	mu      sync.Mutex
	keys    map[string]*rsa.PublicKey
	fetched time.Time
}

func newGoogleVerifier(clientID string) *googleVerifier {
	return &googleVerifier{clientID: clientID}
}

func (g *googleVerifier) verify(ctx context.Context, idToken string) (googleClaims, error) {
	if g.clientID == "" {
		return googleClaims{}, errors.New("GOOGLE_WEB_CLIENT_ID is not configured")
	}
	var claims struct {
		jwt.RegisteredClaims
		Email         string `json:"email"`
		EmailVerified bool   `json:"email_verified"`
		Name          string `json:"name"`
	}
	_, err := jwt.ParseWithClaims(idToken, &claims, func(t *jwt.Token) (any, error) {
		kid, _ := t.Header["kid"].(string)
		return g.key(ctx, kid)
	}, jwt.WithValidMethods([]string{"RS256"}), jwt.WithAudience(g.clientID), jwt.WithExpirationRequired())
	if err != nil {
		return googleClaims{}, err
	}
	if iss := claims.Issuer; iss != "https://accounts.google.com" && iss != "accounts.google.com" {
		return googleClaims{}, fmt.Errorf("unexpected issuer %q", iss)
	}
	if claims.Subject == "" || claims.Email == "" || !claims.EmailVerified {
		return googleClaims{}, errors.New("token lacks a verified email")
	}
	return googleClaims{Sub: claims.Subject, Email: claims.Email, Name: claims.Name}, nil
}

// key returns the signing key for kid, refetching Google's key set at most once a minute.
func (g *googleVerifier) key(ctx context.Context, kid string) (*rsa.PublicKey, error) {
	g.mu.Lock()
	defer g.mu.Unlock()
	if k, ok := g.keys[kid]; ok && time.Since(g.fetched) < time.Hour {
		return k, nil
	}
	if time.Since(g.fetched) > time.Minute || g.keys == nil {
		if err := g.fetch(ctx); err != nil {
			return nil, err
		}
	}
	if k, ok := g.keys[kid]; ok {
		return k, nil
	}
	return nil, errors.New("unknown signing key")
}

func (g *googleVerifier) fetch(ctx context.Context) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, googleCertsURL, nil)
	if err != nil {
		return err
	}
	resp, err := (&http.Client{Timeout: 10 * time.Second}).Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	var set struct {
		Keys []struct{ Kid, N, E string } `json:"keys"`
	}
	if resp.StatusCode != http.StatusOK || json.NewDecoder(resp.Body).Decode(&set) != nil {
		return fmt.Errorf("fetching Google keys: status %d", resp.StatusCode)
	}
	keys := map[string]*rsa.PublicKey{}
	for _, k := range set.Keys {
		n, err1 := base64.RawURLEncoding.DecodeString(k.N)
		e, err2 := base64.RawURLEncoding.DecodeString(k.E)
		if err1 != nil || err2 != nil {
			continue
		}
		keys[k.Kid] = &rsa.PublicKey{N: new(big.Int).SetBytes(n), E: int(new(big.Int).SetBytes(e).Int64())}
	}
	g.keys, g.fetched = keys, time.Now()
	return nil
}
