-- CoffeeAI backend — Stage 2 migration: memory foundation.
-- Non-destructive. Depends on 0001_devices.sql. Idempotent (safe to re-run).
-- No embeddings, no memory_conflicts, no user accounts, no raw audio.

create extension if not exists "pgcrypto";

-- 1) Durable device-owned memory items ---------------------------------------------------------
create table if not exists public.memory_items (
    id                uuid        primary key default gen_random_uuid(),
    device_id         uuid        not null references public.devices(id) on delete cascade,
    type              text        not null,
    content           text        not null,
    summary           text,
    confidence        numeric     not null default 1.0,
    importance        numeric     not null default 0.5,
    status            text        not null default 'proposed',
    source            text        not null,
    source_session_id text,
    language          text,
    product_version   text,
    sensitivity       text        not null default 'normal',
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),
    last_used_at      timestamptz,
    deleted_at        timestamptz,
    metadata          jsonb       not null default '{}'::jsonb,
    constraint memory_items_type_chk
        check (type in ('profile', 'episodic', 'semantic', 'procedural', 'preference', 'safety')),
    constraint memory_items_status_chk
        check (status in ('proposed', 'approved', 'superseded', 'rejected', 'deleted'))
);
create index if not exists memory_items_device_idx on public.memory_items (device_id);
create index if not exists memory_items_type_idx on public.memory_items (type);
create index if not exists memory_items_status_idx on public.memory_items (status);
create index if not exists memory_items_session_idx on public.memory_items (source_session_id);
create index if not exists memory_items_updated_idx on public.memory_items (updated_at);
create index if not exists memory_items_device_status_type_idx
    on public.memory_items (device_id, status, type);

-- 2) Rolling per-session summaries --------------------------------------------------------------
create table if not exists public.conversation_summaries (
    id            uuid        primary key default gen_random_uuid(),
    device_id     uuid        not null references public.devices(id) on delete cascade,
    session_id    text        not null,
    summary       text        not null,
    covered_until text,
    version       integer     not null default 1,
    token_estimate integer    not null default 0,
    language      text,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now(),
    constraint conversation_summaries_device_session_key unique (device_id, session_id)
);
create index if not exists conversation_summaries_device_idx on public.conversation_summaries (device_id);
create index if not exists conversation_summaries_updated_idx on public.conversation_summaries (updated_at);

-- 3) Global, read-only product knowledge documents ----------------------------------------------
create table if not exists public.knowledge_documents (
    id          uuid        primary key default gen_random_uuid(),
    product     text        not null,
    title       text        not null,
    version     text        not null,
    locale      text        not null,
    trust_level text        not null,
    source      text        not null,
    status      text        not null default 'active',
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now(),
    metadata    jsonb       not null default '{}'::jsonb
);
create index if not exists knowledge_documents_product_idx
    on public.knowledge_documents (product, version, locale);
create index if not exists knowledge_documents_status_idx on public.knowledge_documents (status);

-- 4) Chunks of knowledge documents --------------------------------------------------------------
create table if not exists public.knowledge_chunks (
    id             uuid        primary key default gen_random_uuid(),
    document_id    uuid        not null references public.knowledge_documents(id) on delete cascade,
    chunk_index    integer     not null,
    title          text,
    content        text        not null,
    token_estimate integer     not null default 0,
    metadata       jsonb       not null default '{}'::jsonb
);
create index if not exists knowledge_chunks_document_idx on public.knowledge_chunks (document_id);

-- 5) Privacy-safe audit log ---------------------------------------------------------------------
create table if not exists public.memory_audit_log (
    id         uuid        primary key default gen_random_uuid(),
    device_id  uuid,
    request_id uuid        not null,
    actor      text        not null,
    action     text        not null,
    memory_id  uuid,
    session_id text,
    details    jsonb       not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);
create index if not exists memory_audit_device_idx on public.memory_audit_log (device_id);
create index if not exists memory_audit_request_idx on public.memory_audit_log (request_id);

-- RLS: deny-by-default for anon/other keys. The FastAPI backend uses the service-role key, which
-- BYPASSES RLS, so application-level device_id filters (in memory_store) are the mandatory control.
alter table public.memory_items enable row level security;
alter table public.conversation_summaries enable row level security;
alter table public.knowledge_documents enable row level security;
alter table public.knowledge_chunks enable row level security;
alter table public.memory_audit_log enable row level security;
