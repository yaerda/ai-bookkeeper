create table if not exists family_ledger (
    owner_id uuid primary key references app_user(id),
    name text not null default '家庭账本',
    mode text not null default 'PERSONAL',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint family_ledger_name_not_blank
        check (length(trim(name)) between 1 and 100),
    constraint family_ledger_mode
        check (mode in ('PERSONAL', 'FAMILY'))
);

create table if not exists ledger_member (
    id uuid primary key default gen_random_uuid(),
    owner_id uuid not null references app_user(id),
    member_id uuid not null references app_user(id),
    role text not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ledger_member_role
        check (role in ('VIEWER', 'EDITOR')),
    constraint ledger_member_not_owner
        check (owner_id <> member_id),
    unique (owner_id, member_id)
);

create index if not exists ledger_member_by_member
    on ledger_member (member_id);

create table if not exists ledger_invitation (
    id uuid primary key default gen_random_uuid(),
    owner_id uuid not null references app_user(id),
    invited_email text not null,
    role text not null,
    created_at timestamptz not null default now(),
    constraint ledger_invitation_email_is_lowercase
        check (invited_email = lower(trim(invited_email))),
    constraint ledger_invitation_role
        check (role in ('VIEWER', 'EDITOR')),
    unique (owner_id, invited_email)
);

create index if not exists ledger_invitation_by_email
    on ledger_invitation (invited_email);
