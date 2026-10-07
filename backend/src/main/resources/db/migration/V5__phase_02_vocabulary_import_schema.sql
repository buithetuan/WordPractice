create table vocabulary_pronunciations (
    id uuid primary key default gen_random_uuid(),
    vocabulary_id uuid references vocabularies(id) on delete cascade,
    vocabulary_sense_id uuid references vocabulary_senses(id) on delete cascade,
    accent varchar(8) not null,
    ipa varchar(255),
    stress_pattern varchar(120),
    audio_path varchar(500),
    content_type varchar(80),
    source_type varchar(24) not null default 'DICTIONARY',
    source_reference varchar(500),
    created_at timestamptz not null default now(),
    constraint ck_vocabulary_pronunciation_target check (
        (vocabulary_id is not null and vocabulary_sense_id is null)
        or (vocabulary_id is null and vocabulary_sense_id is not null)
    ),
    constraint ck_vocabulary_pronunciation_accent check (accent in ('UK', 'US')),
    constraint ck_vocabulary_pronunciation_source check (source_type in ('USER', 'USER_IMPORT', 'PLATFORM', 'DICTIONARY', 'AI'))
);
create unique index uq_vocabulary_pronunciation_word_accent
    on vocabulary_pronunciations (vocabulary_id, accent) where vocabulary_id is not null;
create unique index uq_vocabulary_pronunciation_sense_accent
    on vocabulary_pronunciations (vocabulary_sense_id, accent) where vocabulary_sense_id is not null;

create table vocabulary_related_words (
    id uuid primary key default gen_random_uuid(),
    vocabulary_id uuid not null references vocabularies(id) on delete cascade,
    related_vocabulary_id uuid not null references vocabularies(id) on delete restrict,
    relation_type varchar(32) not null,
    source_type varchar(24) not null default 'USER',
    created_at timestamptz not null default now(),
    constraint uq_vocabulary_related_words unique (vocabulary_id, related_vocabulary_id, relation_type),
    constraint ck_vocabulary_related_words_distinct check (vocabulary_id <> related_vocabulary_id),
    constraint ck_vocabulary_related_words_type check (relation_type in ('SYNONYM', 'ANTONYM', 'WORD_FAMILY', 'CONFUSED_WITH', 'TOPIC_RELATED')),
    constraint ck_vocabulary_related_words_source check (source_type in ('USER', 'USER_IMPORT', 'PLATFORM', 'DICTIONARY', 'AI'))
);

create table import_jobs (
    id uuid primary key default gen_random_uuid(),
    owner_user_id uuid not null references users(id) on delete restrict,
    vocabulary_set_id uuid references vocabulary_sets(id) on delete set null,
    original_filename varchar(255) not null,
    file_sha256 char(64) not null,
    status varchar(32) not null default 'PREVIEW_READY',
    detected_encoding varchar(40) not null,
    encoding_repair_proposed boolean not null default false,
    column_mapping jsonb not null default '{}'::jsonb,
    row_count integer not null default 0,
    imported_count integer not null default 0,
    rejected_count integer not null default 0,
    created_at timestamptz not null default now(),
    confirmed_at timestamptz,
    constraint ck_import_jobs_status check (status in ('PREVIEW_READY', 'CONFIRMED', 'COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED')),
    constraint ck_import_jobs_counts check (row_count >= 0 and imported_count >= 0 and rejected_count >= 0)
);

create table import_job_rows (
    id uuid primary key default gen_random_uuid(),
    import_job_id uuid not null references import_jobs(id) on delete cascade,
    row_number integer not null,
    raw_data jsonb not null,
    normalized_data jsonb not null,
    status varchar(32) not null,
    issues jsonb not null default '[]'::jsonb,
    vocabulary_id uuid references vocabularies(id) on delete set null,
    vocabulary_sense_id uuid references vocabulary_senses(id) on delete set null,
    created_at timestamptz not null default now(),
    constraint uq_import_job_row_number unique (import_job_id, row_number),
    constraint ck_import_job_rows_status check (status in ('VALID', 'INVALID', 'AMBIGUOUS', 'IMPORTED'))
);
create index ix_import_jobs_owner_created on import_jobs (owner_user_id, created_at desc);

create table enrichment_jobs (
    id uuid primary key default gen_random_uuid(),
    owner_user_id uuid not null references users(id) on delete restrict,
    vocabulary_id uuid not null references vocabularies(id) on delete restrict,
    vocabulary_sense_id uuid,
    provider varchar(32) not null,
    model varchar(100),
    status varchar(24) not null default 'SUGGESTED',
    input_snapshot jsonb not null,
    output_payload jsonb not null,
    error_message text,
    created_at timestamptz not null default now(),
    applied_at timestamptz,
    constraint fk_enrichment_jobs_sense_vocabulary
        foreign key (vocabulary_sense_id, vocabulary_id)
        references vocabulary_senses(id, vocabulary_id) on delete restrict,
    constraint ck_enrichment_jobs_status check (status in ('SUGGESTED', 'APPLIED', 'REJECTED', 'FAILED'))
);
create index ix_enrichment_jobs_owner_created on enrichment_jobs (owner_user_id, created_at desc);
