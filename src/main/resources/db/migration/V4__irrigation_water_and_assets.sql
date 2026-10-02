-- Irrigation planning, water accounting, weather cache, water infrastructure and maintenance.
-- Recommendations run on a FAO-56 soil-water balance today; sensors and ML models plug into the
-- same tables later (sensor readings reset depletion, model predictions land in `predictions`).

-- Farm context --------------------------------------------------------------------------------
alter table farms
    add column latitude               numeric(9, 6) check (latitude between -90 and 90),
    add column longitude              numeric(9, 6) check (longitude between -180 and 180),
    add column timezone               varchar(64)   not null default 'Africa/Harare',
    add column currency               varchar(3)    not null default 'USD',
    add column electricity_tariff     numeric(10, 4) check (electricity_tariff >= 0), -- per kWh
    -- Conventional calendar schedule the farm is compared against to report water savings.
    add column baseline_interval_days integer       not null default 3 check (baseline_interval_days > 0),
    add column baseline_depth_mm      numeric(6, 2) not null default 25 check (baseline_depth_mm > 0);

alter table crops
    add column expected_yield_t_ha numeric(7, 2) check (expected_yield_t_ha > 0);

-- Water infrastructure ------------------------------------------------------------------------
create table assets
(
    id                         uuid primary key,
    farm_id                    uuid          not null references farms (id) on delete cascade,
    field_id                   uuid references fields (id) on delete set null,
    parent_id                  uuid references assets (id) on delete set null,
    asset_type                 varchar(32)   not null,
    name                       varchar(160)  not null,
    manufacturer               varchar(120),
    model                      varchar(120),
    serial_number              varchar(120),
    installed_on               date,
    criticality                varchar(16)   not null,
    status                     varchar(16)   not null,
    power_source               varchar(16),
    rated_power_kw             numeric(8, 2) check (rated_power_kw > 0),
    rated_flow_m3h             numeric(10, 2) check (rated_flow_m3h > 0),
    rated_head_m               numeric(8, 2) check (rated_head_m > 0),
    capacity_m3                numeric(14, 2) check (capacity_m3 > 0),
    annual_allocation_m3       numeric(14, 2) check (annual_allocation_m3 > 0),
    operating_window_start     time,
    operating_window_end       time,
    service_interval_days      integer check (service_interval_days > 0),
    service_interval_hours     integer check (service_interval_hours > 0),
    expected_life_years        integer check (expected_life_years > 0),
    operating_hours            numeric(10, 1) not null default 0 check (operating_hours >= 0),
    hours_at_last_service      numeric(10, 1) not null default 0 check (hours_at_last_service >= 0),
    last_service_on            date,
    baseline_energy_kwh_per_m3 numeric(8, 4) check (baseline_energy_kwh_per_m3 > 0),
    notes                      varchar(1000),
    created_at                 timestamptz   not null,
    updated_at                 timestamptz   not null,
    version                    bigint        not null default 0
);

create index ix_assets_farm on assets (farm_id);
create index ix_assets_parent on assets (parent_id);

-- Manual inspections today; the same shape later receives sensor-derived snapshots.
create table asset_condition_checks
(
    id                uuid primary key,
    asset_id          uuid          not null references assets (id) on delete cascade,
    checked_at        timestamptz   not null,
    overall_condition varchar(16)   not null,
    pressure_bar      numeric(6, 2) check (pressure_bar >= 0),
    flow_m3h          numeric(10, 2) check (flow_m3h >= 0),
    power_kw          numeric(8, 2) check (power_kw >= 0),
    hour_meter        numeric(10, 1) check (hour_meter >= 0),
    vibration         varchar(16),
    leak_detected     boolean       not null default false,
    filter_dp_bar     numeric(5, 2) check (filter_dp_bar >= 0),
    water_level_m     numeric(7, 2),
    notes             varchar(1000),
    recorded_by       uuid references users (id) on delete set null,
    created_at        timestamptz   not null
);

create index ix_condition_checks_asset_time on asset_condition_checks (asset_id, checked_at desc);

create sequence work_order_number_seq start with 1001;

create table work_orders
(
    id               uuid primary key,
    number           bigint        not null default nextval('work_order_number_seq'),
    farm_id          uuid          not null references farms (id) on delete cascade,
    asset_id         uuid references assets (id) on delete set null,
    alert_id         uuid references alerts (id) on delete set null,
    kind             varchar(16)   not null,
    priority         varchar(16)   not null,
    status           varchar(16)   not null,
    title            varchar(200)  not null,
    description      varchar(2000),
    due_on           date,
    assignee         varchar(160),
    created_by       uuid references users (id) on delete set null,
    started_at       timestamptz,
    completed_at     timestamptz,
    completion_notes varchar(2000),
    cost             numeric(12, 2) check (cost >= 0),
    downtime_hours   numeric(8, 2) check (downtime_hours >= 0),
    created_at       timestamptz   not null,
    updated_at       timestamptz   not null,
    version          bigint        not null default 0,
    constraint uq_work_orders_number unique (number)
);

create index ix_work_orders_farm_status on work_orders (farm_id, status);
create index ix_work_orders_asset on work_orders (asset_id);

-- Irrigation ------------------------------------------------------------------------------------
alter table fields
    add column irrigation_method     varchar(32),
    add column application_rate_mm_h numeric(6, 2) check (application_rate_mm_h > 0),
    add column system_efficiency     numeric(3, 2) check (system_efficiency between 0.30 and 1.00),
    add column pump_asset_id         uuid references assets (id) on delete set null;

alter table irrigation_events
    add column status         varchar(16)   not null default 'COMPLETED',
    add column gross_depth_mm numeric(7, 2) check (gross_depth_mm >= 0),
    add column pump_asset_id  uuid references assets (id) on delete set null,
    add column energy_kwh     numeric(10, 2) check (energy_kwh >= 0),
    add column notes          varchar(500),
    add column recorded_by    uuid references users (id) on delete set null,
    add column completed_at   timestamptz;

create index ix_irrigation_events_status_start on irrigation_events (status, started_at);

-- Farmer calibration of the water balance: "feel" checks, probes, and later sensors.
create table soil_moisture_observations
(
    id            uuid primary key,
    field_id      uuid          not null references fields (id) on delete cascade,
    observed_at   timestamptz   not null,
    depletion_pct numeric(5, 2) not null check (depletion_pct between 0 and 100),
    method        varchar(16)   not null,
    notes         varchar(500),
    recorded_by   uuid references users (id) on delete set null,
    created_at    timestamptz   not null
);

create index ix_soil_observations_field_time on soil_moisture_observations (field_id, observed_at desc);

-- Daily weather per farm: Open-Meteo when reachable, deterministic climatology otherwise.
create table weather_daily
(
    farm_id                   uuid         not null references farms (id) on delete cascade,
    day                       date         not null,
    et0_mm                    numeric(5, 2),
    precipitation_mm          numeric(6, 2),
    precipitation_probability smallint,
    temp_max_c                numeric(4, 1),
    temp_min_c                numeric(4, 1),
    source                    varchar(16)  not null,
    fetched_at                timestamptz  not null,
    primary key (farm_id, day)
);

-- Alerts ----------------------------------------------------------------------------------------
alter table alerts
    add column asset_id      uuid references assets (id) on delete cascade,
    add column title         varchar(200),
    add column dedupe_key    varchar(160),
    add column source        varchar(16) not null default 'RULES',
    add column work_order_id uuid references work_orders (id) on delete set null,
    add column updated_at    timestamptz;

-- At most one open alert per condition: evaluations update it instead of piling up duplicates.
create unique index ux_alerts_open_dedupe on alerts (farm_id, dedupe_key) where resolved_at is null;
