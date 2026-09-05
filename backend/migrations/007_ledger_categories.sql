create table if not exists ledger_category (
    id integer generated always as identity primary key check (id > 0),
    ledger_id uuid not null references family_ledger(id) on delete cascade,
    name text not null check (char_length(name) between 1 and 100),
    type text not null check (type in ('EXPENSE', 'INCOME')),
    icon text not null check (char_length(icon) between 1 and 64),
    color text not null check (color ~ '^#[0-9a-fA-F]{6}$'),
    sort_order integer not null default 1000
        check (sort_order between 0 and 1000000),
    is_system boolean not null default false,
    created_at timestamptz not null default now(),
    constraint ledger_category_ledger_type_name_key
        unique (ledger_id, type, name)
);

create index if not exists ledger_transaction_active_categories
    on ledger_transaction (ledger_id)
    where deleted_at is null;
