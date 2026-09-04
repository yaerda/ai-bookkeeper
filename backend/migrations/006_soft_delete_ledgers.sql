alter table family_ledger
    add column if not exists deleted_at timestamptz;

create index if not exists family_ledger_active_by_owner
    on family_ledger (owner_id, is_default)
    where deleted_at is null;
