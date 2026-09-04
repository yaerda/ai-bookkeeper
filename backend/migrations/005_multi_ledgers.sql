alter table family_ledger
    add column if not exists id uuid;

alter table family_ledger
    add column if not exists is_default boolean not null default false;

insert into family_ledger (id, owner_id, is_default)
select app_user.id, app_user.id, true
  from app_user
 where not exists (
     select 1
       from family_ledger
      where family_ledger.owner_id = app_user.id
 );

update family_ledger
   set id = owner_id
 where id is null;

alter table family_ledger
    alter column id set default gen_random_uuid();

update family_ledger
   set is_default = true
 where id = owner_id
   and not exists (
       select 1
         from family_ledger existing
        where existing.owner_id = family_ledger.owner_id
          and existing.is_default
   );

alter table family_ledger
    alter column id set not null;

do $$
begin
    if exists (
        select 1
          from pg_constraint
         where conrelid = 'family_ledger'::regclass
           and contype = 'p'
           and conkey = array[
               (select attnum
                  from pg_attribute
                 where attrelid = 'family_ledger'::regclass
                   and attname = 'owner_id')
           ]::smallint[]
    ) then
        alter table family_ledger drop constraint family_ledger_pkey;
    end if;
end;
$$;

do $$
begin
    if not exists (
        select 1
          from pg_constraint
         where conrelid = 'family_ledger'::regclass
           and contype = 'p'
    ) then
        alter table family_ledger
            add constraint family_ledger_pkey primary key (id);
    end if;
end;
$$;

create index if not exists family_ledger_by_owner
    on family_ledger (owner_id);

create unique index if not exists family_ledger_one_default_per_owner
    on family_ledger (owner_id)
    where is_default;

alter table ledger_transaction
    add column if not exists ledger_id uuid;

update ledger_transaction
   set ledger_id = owner_id
 where ledger_id is null;

alter table ledger_transaction
    alter column ledger_id set not null;

do $$
begin
    if not exists (
        select 1
          from pg_constraint
         where conrelid = 'ledger_transaction'::regclass
           and conname = 'ledger_transaction_ledger_id_fkey'
    ) then
        alter table ledger_transaction
            add constraint ledger_transaction_ledger_id_fkey
            foreign key (ledger_id) references family_ledger(id);
    end if;
end;
$$;

do $$
begin
    if exists (
        select 1
          from pg_constraint
         where conrelid = 'ledger_transaction'::regclass
           and contype = 'p'
           and conname = 'ledger_transaction_pkey'
    ) then
        alter table ledger_transaction drop constraint ledger_transaction_pkey;
    end if;
    if not exists (
        select 1
          from pg_constraint
         where conrelid = 'ledger_transaction'::regclass
           and contype = 'p'
    ) then
        alter table ledger_transaction
            add constraint ledger_transaction_pkey
            primary key (ledger_id, sync_id);
    end if;
end;
$$;

create index if not exists ledger_transaction_ledger_change_feed
    on ledger_transaction (ledger_id, server_version);

alter table ledger_member
    add column if not exists ledger_id uuid;

update ledger_member
   set ledger_id = owner_id
 where ledger_id is null;

alter table ledger_member
    alter column ledger_id set not null;

do $$
begin
    if not exists (
        select 1
          from pg_constraint
         where conrelid = 'ledger_member'::regclass
           and conname = 'ledger_member_ledger_id_fkey'
    ) then
        alter table ledger_member
            add constraint ledger_member_ledger_id_fkey
            foreign key (ledger_id) references family_ledger(id) on delete cascade;
    end if;
end;
$$;

alter table ledger_member
    drop constraint if exists ledger_member_owner_id_member_id_key;

create unique index if not exists ledger_member_ledger_member_key
    on ledger_member (ledger_id, member_id);

create index if not exists ledger_member_by_ledger
    on ledger_member (ledger_id);

alter table ledger_invitation
    add column if not exists ledger_id uuid;

update ledger_invitation
   set ledger_id = owner_id
 where ledger_id is null;

alter table ledger_invitation
    alter column ledger_id set not null;

do $$
begin
    if not exists (
        select 1
          from pg_constraint
         where conrelid = 'ledger_invitation'::regclass
           and conname = 'ledger_invitation_ledger_id_fkey'
    ) then
        alter table ledger_invitation
            add constraint ledger_invitation_ledger_id_fkey
            foreign key (ledger_id) references family_ledger(id) on delete cascade;
    end if;
end;
$$;

alter table ledger_invitation
    drop constraint if exists ledger_invitation_owner_id_invited_email_key;

create unique index if not exists ledger_invitation_ledger_email_key
    on ledger_invitation (ledger_id, invited_email);

create index if not exists ledger_invitation_by_ledger
    on ledger_invitation (ledger_id);

create or replace function enforce_family_ledger_default()
returns trigger
language plpgsql
as $$
declare
    affected_owner uuid;
begin
    if tg_op in ('UPDATE', 'DELETE') then
        affected_owner := old.owner_id;
        if exists (
            select 1 from family_ledger where owner_id = affected_owner
        ) and not exists (
            select 1
              from family_ledger
             where owner_id = affected_owner
               and is_default
        ) then
            raise exception 'family ledger owner % must have a default ledger',
                affected_owner;
        end if;
    end if;
    if tg_op in ('INSERT', 'UPDATE') then
        affected_owner := new.owner_id;
        if exists (
            select 1 from family_ledger where owner_id = affected_owner
        ) and not exists (
            select 1
              from family_ledger
             where owner_id = affected_owner
               and is_default
        ) then
            raise exception 'family ledger owner % must have a default ledger',
                affected_owner;
        end if;
    end if;
    return null;
end;
$$;

drop trigger if exists family_ledger_default_required on family_ledger;

create constraint trigger family_ledger_default_required
after insert or update or delete on family_ledger
deferrable initially deferred
for each row execute function enforce_family_ledger_default();
