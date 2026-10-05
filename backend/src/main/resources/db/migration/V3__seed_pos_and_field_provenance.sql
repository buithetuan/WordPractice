insert into part_of_speech (code, name) values
    ('NOUN', 'Noun'),
    ('VERB', 'Verb'),
    ('ADJECTIVE', 'Adjective'),
    ('ADVERB', 'Adverb'),
    ('PRONOUN', 'Pronoun'),
    ('PREPOSITION', 'Preposition'),
    ('CONJUNCTION', 'Conjunction'),
    ('INTERJECTION', 'Interjection'),
    ('DETERMINER', 'Determiner'),
    ('NUMERAL', 'Numeral'),
    ('PARTICLE', 'Particle'),
    ('PHRASE', 'Phrase');

create table lexical_field_provenance (
    id uuid primary key default gen_random_uuid(),
    vocabulary_id uuid references vocabularies(id) on delete cascade,
    vocabulary_sense_id uuid references vocabulary_senses(id) on delete cascade,
    field_name varchar(80) not null,
    source_type varchar(24) not null,
    source_reference varchar(500),
    created_by uuid references users(id) on delete set null,
    created_at timestamptz not null default now(),
    constraint ck_lexical_provenance_target check (
        (vocabulary_id is not null and vocabulary_sense_id is null)
        or (vocabulary_id is null and vocabulary_sense_id is not null)
    ),
    constraint ck_lexical_provenance_source check (
        source_type in ('USER', 'USER_IMPORT', 'PLATFORM', 'DICTIONARY', 'AI')
    )
);

create unique index uq_lexical_provenance_vocabulary_field
    on lexical_field_provenance (vocabulary_id, field_name)
    where vocabulary_id is not null;
create unique index uq_lexical_provenance_sense_field
    on lexical_field_provenance (vocabulary_sense_id, field_name)
    where vocabulary_sense_id is not null;
