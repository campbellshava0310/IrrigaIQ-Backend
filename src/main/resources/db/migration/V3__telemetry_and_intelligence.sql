-- Telemetry, irrigation, predictions and alerts (MVP build guide §6).
-- Tables land now so the synthetic-data generator and ML service can target a stable schema;
-- JPA entities are added alongside each feature module.

create table sensors
(
    id          uuid primary key,
    field_id    uuid         not null references fields (id) on delete cascade,
    sensor_type varchar(32)  not null, -- SOIL_MOISTURE | WEATHER_STATION | PUMP_MONITOR | FLOW_METER
    device_id   varchar(128) not null,
    name        varchar(160),
    status      varchar(16)  not null default 'ACTIVE',
    created_at  timestamptz  not null,
    updated_at  timestamptz  not null,
    version     bigint       not null default 0,
    constraint uq_sensors_device_id unique (device_id)
);

create index ix_sensors_field_id on sensors (field_id);

-- High-volume time series: bigint identity instead of UUID. (sensor_id, recorded_at) is unique so
-- device retries are idempotent, and the same index serves "latest readings" via a backward scan.
create table sensor_readings
(
    id            bigint generated always as identity primary key,
    sensor_id     uuid           not null references sensors (id) on delete cascade,
    recorded_at   timestamptz    not null,
    received_at   timestamptz    not null default now(),
    source        varchar(16)    not null, -- DEVICE | MANUAL | SIMULATED (synthetic data stays labelled)
    soil_moisture numeric(5, 2),           -- volumetric water content, %
    temperature   numeric(5, 2),           -- degrees C
    humidity      numeric(5, 2),           -- relative humidity, %
    rainfall      numeric(7, 2),           -- mm
    flow_rate     numeric(10, 3),          -- L/min
    pressure      numeric(8, 3),           -- bar
    power         numeric(10, 3),          -- kW
    constraint uq_sensor_readings_sensor_time unique (sensor_id, recorded_at)
);

create table predictions
(
    id                           uuid primary key,
    field_id                     uuid           not null references fields (id) on delete cascade,
    predicted_at                 timestamptz    not null,
    water_need_litres            numeric(12, 2) not null,
    irrigation_required          boolean        not null,
    recommended_duration_minutes integer check (recommended_duration_minutes > 0),
    confidence                   numeric(4, 3) check (confidence between 0 and 1),
    model_version                varchar(64)    not null,
    features                     jsonb, -- input snapshot, kept for explainability
    created_at                   timestamptz    not null
);

create index ix_predictions_field_time on predictions (field_id, predicted_at desc);

create table irrigation_events
(
    id               uuid primary key,
    field_id         uuid           not null references fields (id) on delete cascade,
    prediction_id    uuid references predictions (id) on delete set null,
    started_at       timestamptz    not null,
    duration_minutes integer        not null check (duration_minutes > 0),
    estimated_litres numeric(12, 2),
    actual_litres    numeric(12, 2),
    source           varchar(16)    not null, -- RECOMMENDATION | MANUAL | SCHEDULE
    created_at       timestamptz    not null,
    updated_at       timestamptz    not null,
    version          bigint         not null default 0
);

create index ix_irrigation_events_field_started on irrigation_events (field_id, started_at desc);

create table alerts
(
    id              uuid primary key,
    farm_id         uuid        not null references farms (id) on delete cascade,
    field_id        uuid references fields (id) on delete cascade,
    sensor_id       uuid references sensors (id) on delete set null,
    type            varchar(48) not null, -- IRRIGATION_REQUIRED | WATER_ANOMALY | PUMP_RISK | SENSOR_FAULT
    severity        varchar(16) not null, -- INFO | WARNING | CRITICAL
    message         text        not null,
    details         jsonb,
    created_at      timestamptz not null,
    acknowledged_at timestamptz,
    acknowledged_by uuid references users (id) on delete set null,
    resolved_at     timestamptz,
    resolved_by     uuid references users (id) on delete set null
);

create index ix_alerts_farm_created on alerts (farm_id, created_at desc);
create index ix_alerts_farm_open on alerts (farm_id, created_at desc) where resolved_at is null;
