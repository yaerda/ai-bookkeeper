create table if not exists user_privacy (
    user_id uuid primary key references app_user(id) on delete cascade,
    salt text,
    passcode_hash text,
    iterations integer,
    require_on_login boolean not null default false,
    require_for_income boolean not null default false,
    version integer not null default 1 check (version >= 1),
    failed_attempts integer not null default 0
        check (failed_attempts between 0 and 5),
    locked_until timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint user_privacy_credentials_paired check (
        (salt is null and passcode_hash is null and iterations is null)
        or (
            salt is not null and salt ~ '^[0-9a-fA-F]{32}$'
            and passcode_hash is not null
            and passcode_hash ~ '^[0-9a-fA-F]{64}$'
            and iterations is not null and iterations in (120000, 600000)
        )
    ),
    constraint user_privacy_flags_require_passcode check (
        passcode_hash is not null
        or (not require_on_login and not require_for_income)
    ),
    constraint user_privacy_lock_matches_attempts check (
        (locked_until is null and failed_attempts < 5)
        or (locked_until is not null and failed_attempts = 5)
    ),
    constraint user_privacy_attempts_require_passcode check (
        passcode_hash is not null
        or (failed_attempts = 0 and locked_until is null)
    )
);
