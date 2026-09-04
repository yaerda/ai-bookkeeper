alter table app_user
    drop constraint if exists app_user_normalized_email_key;

create index if not exists app_user_normalized_email
    on app_user (normalized_email);
