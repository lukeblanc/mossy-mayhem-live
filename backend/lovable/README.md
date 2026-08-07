# Mossy Mayhem Live — Lovable backend mirror

This folder mirrors the secure backend core generated for Mossy Mayhem Live in Lovable.

Production Lovable backend: `https://mossy-mayhem-live-api.lovable.app`

## Purpose

The Android app must never contain the permanent OpenAI API key. Instead it calls the backend token broker:

- `GET /api/public/health`
- `POST /api/public/realtime-token`

The backend reads `OPENAI_API_KEY` only from the server-side secret store and mints a short-lived OpenAI Realtime client secret. The Android app receives only the short-lived credential.

## Security rules

- Never commit `OPENAI_API_KEY` or any raw `sk-...` / `sk-proj-...` key.
- Never return the permanent key to Android, browser code, logs, or API responses.
- Token responses use no-store caching.
- Upstream OpenAI error bodies are not forwarded.
- Current developer-test rate limit is 20 token requests per 60 seconds per caller.
- Hooks are left for Play Integrity/App Check and future Mossy Time credit enforcement.

## Android integration contract

1. Android calls `POST https://mossy-mayhem-live-api.lovable.app/api/public/realtime-token`.
2. On success, backend returns JSON containing `client_secret`, `expires_at`, `model`, `session_id`, and `request_id`.
3. Android uses the short-lived `client_secret` as its OpenAI Realtime bearer credential.
4. When the credential expires, Android requests another one automatically.

Safe error codes include:

- `MISSING_SERVER_KEY`
- `OPENAI_AUTH_FAILED`
- `OPENAI_BILLING_OR_QUOTA`
- `OPENAI_UPSTREAM_ERROR`
- `RATE_LIMITED`

## Required server secret

`OPENAI_API_KEY`

Configure it only in the Lovable project server secret store. Do not put it in this repository.
