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
- PostgreSQL needs **pgvector** (`pgvector/pgvector:pg18` in compose and Testcontainers):
  `knowledge_chunks.embedding` is `vector(384)`, accessed through `JdbcClient` (not JPA).
- The ML service is optional: `MlClient` returns empty on failure and opens a short circuit
  breaker; every ML view carries `available`, and ML alerts are not auto-resolved while the model
  cannot judge. Tests run with `irrigaiq.ml.enabled=false`.
- The assistant (`assistant/`) talks to its model only through the `ChatModel` port, chosen by
  `irrigaiq.assistant.provider`: `ClaudeChatModel` (SDK adapter: beta messages API,
  `fallbacks: "default"`, adaptive thinking, eager tool input) or `OllamaChatModel` (local model,
  native `/api/chat` NDJSON; recovers tool calls a small model writes as JSON text). Tool input is
  validated by `ToolInputValidator` either way. Farm tools are read-only and run with the asking
  user's permissions. Tests use `ScriptedChatModel`.
- Devices authenticate with `X-Device-Key` on `POST /api/v1/telemetry` (public path in
  `SecurityConfig`); keys are stored as SHA-256 hashes with a lookup prefix.

Verify with `./mvnw verify` (Testcontainers needs Docker). Run locally with
`docker compose up -d && ./mvnw spring-boot:run`.
