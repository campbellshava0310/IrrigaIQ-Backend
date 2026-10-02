-- Devices and telemetry, inputs for the ML service, and the knowledge base behind the assistant.
-- Requires the pgvector extension (compose.yaml and the tests use the pgvector/pgvector image).

create extension if not exists vector;

-- ---- Devices -------------------------------------------------------------------------------------
-- A device belongs to a farm and may sit on a field (soil probe), an asset (pump monitor, flow meter)
-- or both. It authenticates with its own key, stored only as a SHA-256 hash; the short prefix is
-- the lookup handle and is safe to show in the UI.

alter table sensors alter column field_id drop not null;
-- Deleting a field detaches its probes instead of deleting them (and their keys).
alter table sensors drop constraint sensors_field_id_fkey;
alter table sensors add constraint fk_sensors_field foreign key (field_id) references fields (id) on delete set null;
alter table sensors add column farm_id uuid references farms (id) on delete cascade;
update sensors s set farm_id = f.farm_id from fields f where f.id = s.field_id;
alter table sensors alter column farm_id set not null;

alter table sensors add column asset_id uuid references assets (id) on delete set null;
alter table sensors add column key_prefix varchar(16);
alter table sensors add column key_hash varchar(64);
alter table sensors add column key_rotated_at timestamptz;
alter table sensors add column last_seen_at timestamptz;
-- Soil probes read on their own scale: the reading at field capacity (after a full irrigation has
-- drained) and at wilting point turn a reading into root-zone depletion.
alter table sensors add column field_capacity_pct numeric(5, 2);
alter table sensors add column wilting_point_pct numeric(5, 2);

alter table sensors drop constraint uq_sensors_device_id;
alter table sensors add constraint uq_sensors_farm_device unique (farm_id, device_id);
alter table sensors add constraint ck_sensors_type
    check (sensor_type in ('SOIL_MOISTURE', 'WEATHER_STATION', 'PUMP_MONITOR', 'FLOW_METER'));
alter table sensors add constraint ck_sensors_status check (status in ('ACTIVE', 'DISABLED'));
alter table sensors add constraint ck_sensors_calibration
    check (field_capacity_pct is null or wilting_point_pct is null or field_capacity_pct > wilting_point_pct);

create unique index uq_sensors_key_prefix on sensors (key_prefix);
create index ix_sensors_farm on sensors (farm_id);
create index ix_sensors_asset on sensors (asset_id) where asset_id is not null;

alter table sensor_readings add column volume_litres numeric(12, 3);  -- litres since the previous reading
alter table sensor_readings add column vibration numeric(6, 2);       -- mm/s RMS
alter table sensor_readings add column motor_temperature numeric(5, 2); -- degrees C
alter table sensor_readings add constraint ck_sensor_readings_source
    check (source in ('DEVICE', 'MANUAL', 'SIMULATED'));

-- ---- ML inputs -------------------------------------------------------------------------------------

alter table fields add column mulched boolean not null default false;
alter table weather_daily add column wind_max_kmh numeric(5, 1);

-- ---- Knowledge base (retrieval for the assistant) -------------------------------------------------
-- Curated documents shipped with the application (src/main/resources/knowledge). Re-indexed when
-- a document's checksum changes. Chunks without an embedding are still found by full-text search.

create table knowledge_documents
(
    id         uuid primary key,
    slug       varchar(120) not null,
    title      varchar(200) not null,
    sources    jsonb        not null default '[]', -- [{ "title", "publisher", "url" }]
    checksum   varchar(64)  not null,
    indexed_at timestamptz  not null,
    constraint uq_knowledge_documents_slug unique (slug)
);

create table knowledge_chunks
(
    id              bigint generated always as identity primary key,
    document_id     uuid    not null references knowledge_documents (id) on delete cascade,
    ordinal         integer not null,
    heading         varchar(200),
    content         text    not null,
    embedding       vector(384),
    embedding_model varchar(64),
    search          tsvector generated always as (
        setweight(to_tsvector('english', coalesce(heading, '')), 'A') || to_tsvector('english', content)) stored,
    constraint uq_knowledge_chunks_document_ordinal unique (document_id, ordinal)
);

create index ix_knowledge_chunks_search on knowledge_chunks using gin (search);
create index ix_knowledge_chunks_embedding on knowledge_chunks using hnsw (embedding vector_cosine_ops);
