create table if not exists app_db_probe (
    id bigserial primary key,
    created_at timestamptz not null default now()
);
