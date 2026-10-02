-- Farm management: Farm -> Field -> Crop (MVP build guide §6).

create table farms
(
    id         uuid primary key,
    owner_id   uuid           not null references users (id),
    name       varchar(160)   not null,
    location   varchar(255),
    area_ha    numeric(12, 2) check (area_ha > 0),
    created_at timestamptz    not null,
    updated_at timestamptz    not null,
    version    bigint         not null default 0
);

create index ix_farms_owner_id on farms (owner_id);

create table fields
(
    id         uuid primary key,
    farm_id    uuid           not null references farms (id) on delete cascade,
    name       varchar(160)   not null,
    area_ha    numeric(12, 2) check (area_ha > 0),
    soil_type  varchar(32),
    latitude   numeric(9, 6) check (latitude between -90 and 90),
    longitude  numeric(9, 6) check (longitude between -180 and 180),
    created_at timestamptz    not null,
    updated_at timestamptz    not null,
    version    bigint         not null default 0,
    constraint uq_fields_farm_name unique (farm_id, name)
);

create table crops
(
    id            uuid primary key,
    field_id      uuid         not null references fields (id) on delete cascade,
    crop_type     varchar(32)  not null,
    variety       varchar(120),
    planting_date date,
    growth_stage  varchar(32)  not null, -- FAO-56 stages drive the crop coefficient (Kc) used by the ML service
    created_at    timestamptz  not null,
    updated_at    timestamptz  not null,
    version       bigint       not null default 0
);

create index ix_crops_field_id on crops (field_id);
