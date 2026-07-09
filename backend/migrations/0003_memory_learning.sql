-- CoffeeAI backend — Stage 5 migration: memory learning support (optional / non-destructive).
-- Learning provenance is stored in memory_items.metadata for immediate compatibility.
-- These columns are optional indexes/helpers; application code does not require them to function.
-- Idempotent. Safe to re-run. Does not alter Stage 1–4 behavior.

-- Optional first-class columns for query convenience (nullable; existing rows unaffected).
alter table public.memory_items
    add column if not exists request_id text;
alter table public.memory_items
    add column if not exists source_turn_id text;
alter table public.memory_items
    add column if not exists supersedes_memory_id uuid references public.memory_items(id);
alter table public.memory_items
    add column if not exists reviewed_at timestamptz;
alter table public.memory_items
    add column if not exists reviewed_by text;
alter table public.memory_items
    add column if not exists rejection_reason text;
alter table public.memory_items
    add column if not exists normalized_subject text;

create index if not exists memory_items_request_id_idx
    on public.memory_items (device_id, request_id);
create index if not exists memory_items_supersedes_idx
    on public.memory_items (supersedes_memory_id);
create index if not exists memory_items_subject_idx
    on public.memory_items (device_id, normalized_subject);
