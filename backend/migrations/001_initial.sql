create sequence if not exists sync_version_seq;

create table if not exists app_user (
    id uuid primary key default gen_random_uuid(),
    normalized_email text not null,
    created_at timestamptz not null default now(),
    constraint normalized_email_is_lowercase
        check (normalized_email = lower(trim(normalized_email)))
);

create index if not exists app_user_normalized_email
    on app_user (normalized_email);

create table if not exists auth_principal (
    issuer text not null,
    subject text not null,
    user_id uuid not null unique references app_user(id),
    created_at timestamptz not null default now(),
    primary key (issuer, subject)
);

create index if not exists auth_principal_user
    on auth_principal (user_id);

create table if not exists ledger_transaction (
    owner_id uuid not null references app_user(id),
    sync_id uuid not null,
    server_version bigint not null default nextval('sync_version_seq'),
    payload jsonb not null,
    deleted_at timestamptz,
    updated_at timestamptz not null default now(),
    primary key (owner_id, sync_id)
);

create index if not exists ledger_transaction_change_feed
    on ledger_transaction (owner_id, server_version);

create or replace function assign_sync_version()
returns trigger
language plpgsql
as $$
begin
    new.server_version := nextval('sync_version_seq');
    new.updated_at := now();
    return new;
end;
$$;

drop trigger if exists ledger_transaction_version on ledger_transaction;

create trigger ledger_transaction_version
before update on ledger_transaction
for each row execute function assign_sync_version();
