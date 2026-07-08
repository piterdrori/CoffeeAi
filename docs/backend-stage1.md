# CoffeeAI Backend — Stage 1: durable device identity

Foundation for later stages (Favorites sync, conversation summaries, durable memory, Memory Context
Packets, Hermes). This stage adds a durable store and one identity per Android installation. It does
**not** add Favorites sync, summaries, embeddings, Hermes, or user accounts.

## What it adds
- A `devices` table (Supabase/Postgres) — see `backend/migrations/0001_devices.sql`.
- `POST /v1/devices/register` — idempotent per `install_id`; mints one opaque device token and stores
  only its salted hash. Rate-limited bootstrap path, gated by the legacy shared API key.
- `require_device` bearer-token dependency + `GET /v1/devices/me`.
- `GET /v1/health/database` — reports store connectivity and whether it is durable.
- Non-durable in-memory store fallback so the API still boots without Supabase (local dev / tests).

## Required Vercel environment variables (server-only; never shipped to Android)
| Variable | Purpose | Required |
|---|---|---|
| `SUPABASE_URL` | Supabase project URL (PostgREST base is `${SUPABASE_URL}/rest/v1`) | Yes for durability |
| `SUPABASE_SERVICE_ROLE_KEY` | Server-only service-role key (bypasses RLS) | Yes for durability |
| `DEVICE_TOKEN_SIGNING_SECRET` | Pepper mixed into the device-token hash; set a strong value | Yes (has insecure default) |
| `API_KEY` | Legacy shared key used only as the registration bootstrap gate; **rotate from the default** | Yes |
| `DATA_DIR` | Existing; ephemeral `/tmp` on Vercel | already set |

If `SUPABASE_URL` / `SUPABASE_SERVICE_ROLE_KEY` are unset, the backend uses the in-memory store and
`/v1/health/database` reports `"durable": false`.

## Applying the migration
Run `backend/migrations/0001_devices.sql` in the Supabase SQL editor (or `supabase db` CLI). It is
idempotent and non-destructive.

## Legacy shared-key migration / removal plan
- New app builds register a device and use `Authorization: Bearer <device_token>` for device-scoped
  routes. The shared `X-API-Key` is used **only** for the `POST /v1/devices/register` bootstrap.
- The shared key still gates the remaining **legacy, non-durable, global** memory/sync/config/support
  routes (bounded compatibility window). New durable/device-scoped data must never be authorized by
  the shared key.
- Removal: once the legacy memory routes are migrated to device-scoped durable storage (later
  stages), delete the shared-key branch in `main.py` and remove `API_KEY` bootstrap gating from
  registration (replace with rate-limit + attestation only). Old builds then lose access to new data
  by design.

## Authorization model (Stage 1)
- Device data authorization is enforced in FastAPI using the service-role key. RLS is enabled on
  `devices` as deny-by-default for non-service-role clients; it is not the primary control this stage.
- Only salted token **hashes** are stored. Raw tokens are returned once at registration and never
  logged.
