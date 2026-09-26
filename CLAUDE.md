# IrrigaIQ Backend: working notes

Spring Boot **4.1** / Spring Security **7** / Hibernate **7** / Jackson **3** (`tools.jackson.*`;
annotations stay `com.fasterxml.jackson.annotation`). Don't assume Boot 3 APIs: starters are
modular (`spring-boot-starter-webmvc`, `spring-boot-starter-flyway`,
`spring-boot-starter-security-oauth2-resource-server`) and `ProblemDetail.getType()` may be null.

- Package by feature (`identity`, `farm`, `admin`, `audit`, …); controllers and request/response
  records live in each feature's `web` subpackage.
- Schema changes are new Flyway migrations (`V<n>__description.sql`); never edit an applied one.
  Hibernate runs with `ddl-auto: validate`.
- Services return read models built inside the transaction (see `farm/FarmViews`), never lazy
  entities, to the web layer.
- Expected failures throw `ApiException` with an `ErrorCode`; codes are a public contract.
- Farm-scoped access goes through `FarmService.requireAccessible` (404 for non-owners).
- Record security-relevant actions with `AuditService.record(AuditEntry.of(...))`.
- Use the injected `Clock`, not `Instant.now()`, in business logic.
- Engines (`agronomy/WaterBalanceEngine`, `assets/AssetHealthEngine`) are pure and unit-tested;
  services only load data and map views. Model details: `docs/irrigation-and-maintenance-models.md`.
- User-facing text goes through `common/text/HumanText` (durations, plurals): no "day(s)" or raw
  minutes in messages.
- Modules talk through services, ports (`PumpUsageProvider`, `OpenWorkOrderCounter`) and the
  `FarmDataChanged` event; don't import another module's repositories.
- Hibernate 7's JPA dialect has no savepoints, so `PROPAGATION_NESTED` fails. `WeatherStore` skips
  caching while the caller is itself writing (a `REQUIRES_NEW` write can't see an uncommitted farm).
- Fields and assets reference each other by plain id columns; when inserting both in one
  transaction, flush each layer before the next references it (see `DemoFarmService`).

Verify with `./mvnw verify` (Testcontainers needs Docker). Run locally with
`docker compose up -d && ./mvnw spring-boot:run`.
