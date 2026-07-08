-- CoffeeAI backend — Stage 1 migration: durable device identity.
-- Non-destructive. Creates only the `devices` table (no user/account tables yet).
-- Apply via the Supabase SQL editor or CLI. Idempotent (safe to re-run).

create extension if not exists "pgcrypto";

create table if not exists public.devices (
    id           uuid        primary key default gen_random_uuid(),
    install_id   text        unique not null,
    token_hash   text        not null,
    platform     text        not null default 'android',
    app_version  text,
    created_at   timestamptz not null default now(),
    last_seen_at timestamptz not null default now(),
    revoked_at   timestamptz,
    metadata     jsonb       not null default '{}'::jsonb
);

-- Lookups: by install_id (registration idempotency) and by active token_hash (authentication).
create unique index if not exists devices_install_id_key on public.devices (install_id);
create index if not exists devices_active_token_idx on public.devices (token_hash) where revoked_at is null;

-- Enable RLS so anon / non-service-role keys cannot read device rows. The FastAPI backend uses the
-- service-role key, which bypasses RLS; Stage 1 authorization is enforced in FastAPI. No anon
-- policies are granted here (deny-by-default). Device-scoped RLS is added in a later stage if/when
-- the Android app talks to Supabase directly.
alter table public.devices enable row level security;
