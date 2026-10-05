-- Foundational schema for identity, lexical data, vocabulary sets and learning state.
-- User authentication behavior is implemented in Phase 05; this table provides
-- stable ownership references for the data model from the start.

create table users (
    id uuid primary key default gen_random_uuid(),
    email varchar(320) not null,
    display_name varchar(120) not null,
    password_hash varchar(255),
    status varchar(20) not null default 'ACTIVE',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uq_users_email unique (email),
    constraint ck_users_status check (status in ('ACTIVE', 'DISABLED', 'PENDING'))
);

create table roles (
    id smallint generated always as identity primary key,
    code varchar(30) not null unique,
    name varchar(80) not null
);

create table user_roles (
    user_id uuid not null references users(id) on delete cascade,
    role_id smallint not null references roles(id) on delete restrict,
    assigned_at timestamptz not null default now(),
    primary key (user_id, role_id)
);

insert into roles (code, name) values ('USER', 'User'), ('ADMIN', 'Administrator');

create table part_of_speech (
    id smallint generated always as identity primary key,
    code varchar(30) not null unique,
    name varchar(80) not null
);

create table vocabularies (
    id uuid primary key default gen_random_uuid(),
    language varchar(12) not null,
    lemma varchar(200) not null,
    normalized_lemma varchar(200) not null,
    created_by uuid references users(id) on delete set null,
    source_type varchar(24) not null default 'USER',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uq_vocabularies_language_normalized unique (language, normalized_lemma),
    constraint ck_vocabularies_source check (source_type in ('USER', 'USER_IMPORT', 'PLATFORM', 'DICTIONARY', 'AI'))
);

create table vocabulary_senses (
    id uuid primary key default gen_random_uuid(),
    vocabulary_id uuid not null references vocabularies(id) on delete restrict,
    part_of_speech_id smallint not null references part_of_speech(id) on delete restrict,
    sense_order smallint not null,
    definition_en text,
    explanation_vi text,
    cefr_level varchar(2),
    topic varchar(120),
    register varchar(40),
    source_type varchar(24) not null default 'USER',
    created_by uuid references users(id) on delete set null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uq_vocabulary_senses_order unique (vocabulary_id, sense_order),
    constraint uq_vocabulary_senses_id_vocabulary unique (id, vocabulary_id),
    constraint ck_vocabulary_senses_order check (sense_order > 0),
    constraint ck_vocabulary_senses_cefr check (cefr_level is null or cefr_level in ('A1', 'A2', 'B1', 'B2', 'C1', 'C2')),
    constraint ck_vocabulary_senses_source check (source_type in ('USER', 'USER_IMPORT', 'PLATFORM', 'DICTIONARY', 'AI'))
);

create table vocabulary_examples (
    id uuid primary key default gen_random_uuid(),
    vocabulary_sense_id uuid not null references vocabulary_senses(id) on delete restrict,
    sentence_en text not null,
    translation_vi text,
    source_type varchar(24) not null default 'USER',
    display_order smallint not null default 1,
    created_at timestamptz not null default now(),
    constraint ck_vocabulary_examples_order check (display_order > 0),
    constraint ck_vocabulary_examples_source check (source_type in ('USER', 'USER_IMPORT', 'PLATFORM', 'DICTIONARY', 'AI'))
);

create table vocabulary_sets (
    id uuid primary key default gen_random_uuid(),
    name varchar(200) not null,
    description text,
    owner_user_id uuid references users(id) on delete restrict,
    visibility varchar(20) not null default 'PRIVATE',
    source_type varchar(24) not null default 'USER',
    created_by uuid references users(id) on delete set null,
    updated_by uuid references users(id) on delete set null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz,
    constraint ck_vocabulary_sets_visibility check (visibility in ('PRIVATE', 'SHARED', 'PUBLIC', 'PLATFORM')),
    constraint ck_vocabulary_sets_source check (source_type in ('USER', 'USER_IMPORT', 'PLATFORM', 'DICTIONARY', 'AI')),
    constraint ck_vocabulary_sets_owner check ((source_type in ('PLATFORM', 'DICTIONARY') and owner_user_id is null) or owner_user_id is not null)
);

create table vocabulary_set_items (
    id uuid primary key default gen_random_uuid(),
    vocabulary_set_id uuid not null references vocabulary_sets(id) on delete restrict,
    vocabulary_id uuid not null references vocabularies(id) on delete restrict,
    vocabulary_sense_id uuid,
    added_by uuid references users(id) on delete set null,
    added_at timestamptz not null default now(),
    removed_by uuid references users(id) on delete set null,
    removed_at timestamptz,
    constraint fk_vocabulary_set_items_sense_vocabulary
        foreign key (vocabulary_sense_id, vocabulary_id)
        references vocabulary_senses(id, vocabulary_id) on delete restrict,
    constraint ck_vocabulary_set_items_removal check ((removed_at is null) = (removed_by is null))
);
create unique index uq_active_vocabulary_set_item
    on vocabulary_set_items (vocabulary_set_id, vocabulary_id, coalesce(vocabulary_sense_id, '00000000-0000-0000-0000-000000000000'::uuid))
    where removed_at is null;

create table user_excluded_set_items (
    user_id uuid not null references users(id) on delete cascade,
    vocabulary_set_item_id uuid not null references vocabulary_set_items(id) on delete restrict,
    excluded_at timestamptz not null default now(),
    restored_at timestamptz,
    primary key (user_id, vocabulary_set_item_id)
);

create table learning_sessions (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references users(id) on delete restrict,
    vocabulary_set_id uuid references vocabulary_sets(id) on delete set null,
    activity_type varchar(30) not null,
    status varchar(20) not null default 'IN_PROGRESS',
    started_at timestamptz not null default now(),
    completed_at timestamptz,
    constraint ck_learning_sessions_status check (status in ('IN_PROGRESS', 'COMPLETED', 'ABANDONED'))
);

create table learning_events (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references users(id) on delete restrict,
    session_id uuid references learning_sessions(id) on delete set null,
    vocabulary_id uuid not null references vocabularies(id) on delete restrict,
    vocabulary_sense_id uuid,
    activity_type varchar(30) not null,
    result varchar(20) not null,
    response_time_ms integer,
    attempts smallint not null default 1,
    hints_used smallint not null default 0,
    created_at timestamptz not null default now(),
    constraint fk_learning_events_sense_vocabulary
        foreign key (vocabulary_sense_id, vocabulary_id)
        references vocabulary_senses(id, vocabulary_id) on delete restrict,
    constraint ck_learning_events_result check (result in ('CORRECT', 'INCORRECT', 'SKIPPED')),
    constraint ck_learning_events_response_time check (response_time_ms is null or response_time_ms >= 0),
    constraint ck_learning_events_attempts check (attempts > 0 and hints_used >= 0)
);

create table user_srs_states (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references users(id) on delete restrict,
    vocabulary_id uuid not null references vocabularies(id) on delete restrict,
    vocabulary_sense_id uuid,
    next_review_at timestamptz not null,
    stability double precision,
    difficulty double precision,
    interval_days integer,
    due_count integer not null default 0,
    last_reviewed_at timestamptz,
    constraint fk_user_srs_states_sense_vocabulary
        foreign key (vocabulary_sense_id, vocabulary_id)
        references vocabulary_senses(id, vocabulary_id) on delete restrict,
    constraint ck_user_srs_states_values check (due_count >= 0 and (interval_days is null or interval_days >= 0))
);
create unique index uq_user_srs_word_state
    on user_srs_states (user_id, vocabulary_id) where vocabulary_sense_id is null;
create unique index uq_user_srs_sense_state
    on user_srs_states (user_id, vocabulary_id, vocabulary_sense_id) where vocabulary_sense_id is not null;

create table user_mastery_states (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references users(id) on delete restrict,
    vocabulary_id uuid not null references vocabularies(id) on delete restrict,
    vocabulary_sense_id uuid,
    meaning_score numeric(5,2) not null default 0,
    recognition_score numeric(5,2) not null default 0,
    spelling_score numeric(5,2) not null default 0,
    listening_score numeric(5,2) not null default 0,
    pronunciation_score numeric(5,2),
    overall_score numeric(5,2) not null default 0,
    updated_at timestamptz not null default now(),
    constraint fk_user_mastery_states_sense_vocabulary
        foreign key (vocabulary_sense_id, vocabulary_id)
        references vocabulary_senses(id, vocabulary_id) on delete restrict,
    constraint ck_user_mastery_scores check (
        meaning_score between 0 and 100 and recognition_score between 0 and 100
        and spelling_score between 0 and 100 and listening_score between 0 and 100
        and (pronunciation_score is null or pronunciation_score between 0 and 100)
        and overall_score between 0 and 100
    )
);
create unique index uq_user_mastery_word_state
    on user_mastery_states (user_id, vocabulary_id) where vocabulary_sense_id is null;
create unique index uq_user_mastery_sense_state
    on user_mastery_states (user_id, vocabulary_id, vocabulary_sense_id) where vocabulary_sense_id is not null;

create index ix_vocabulary_senses_vocabulary on vocabulary_senses (vocabulary_id, sense_order);
create index ix_vocabulary_set_items_set on vocabulary_set_items (vocabulary_set_id) where removed_at is null;
create index ix_learning_events_user_created on learning_events (user_id, created_at desc);
create index ix_learning_events_vocabulary on learning_events (vocabulary_id, vocabulary_sense_id);
create index ix_user_srs_due on user_srs_states (user_id, next_review_at);
create index ix_user_mastery_user on user_mastery_states (user_id, overall_score);
