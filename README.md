# IrrigaIQ Backend

REST API for the IrrigaIQ farm water-intelligence platform. It is the single system of record for
the web app ([IrrigaIQ-Web](https://github.com/campbellshava0310/IrrigaIQ-Web)) and the mobile app
([IrrigaIQ-MobileApp](https://github.com/campbellshava0310/IrrigaIQ-MobileApp)).

**Stack:** Java 17 · Spring Boot 4.1 · Spring Security 7 (JWT resource server) · PostgreSQL 18 ·
Flyway · Hibernate 7 · springdoc OpenAPI · Testcontainers

## Quick start

Prerequisites: JDK 17+, Docker.

```bash
cp .env.example .env          # local settings shared by Docker Compose and Spring
docker compose up -d          # PostgreSQL 18 on 127.0.0.1:5432
./mvnw spring-boot:run        # API on http://localhost:8080 (dev profile)
```

| URL | Purpose |
| --- | --- |
| http://localhost:8080/swagger-ui.html | Interactive API docs (dev only) |
| http://localhost:8080/v3/api-docs | OpenAPI contract the clients are built against |
| http://localhost:8080/actuator/health | Liveness/readiness (details for admins only) |
| http://localhost:8080/.well-known/jwks.json | Public key for verifying access tokens |

On first start the `dev` profile creates an administrator from `BOOTSTRAP_ADMIN_EMAIL` /
`BOOTSTRAP_ADMIN_PASSWORD` (defaults: `admin@irrigaiq.local` / `IrrigaIQ-Admin-2026!`).

In IntelliJ, use the shared run configuration **IrrigaIQ Backend (dev)** (`.run/`). Running the
main class without a profile fails fast by design: production settings have no defaults for
secrets.

To run the API in a container as well: `docker compose --profile app up -d --build`.

## Architecture

A modular monolith, packaged by feature. Each module owns its entities, repositories, services
and `web` adapters; cross-module calls go through services, never through another module's tables.

| Package | Responsibility |
| --- | --- |
| `common` | Error model (RFC 9457), request-id correlation, pagination, clock, JPA auditing, OpenAPI |
| `security` | Filter chain, RS256 key management, access-token issuing, principal, roles |
| `identity` | Users, sign-in/registration, refresh-token sessions, account self-service, bootstrap admin |
| `audit` | Append-only audit trail of security-relevant events |
| `admin` | User administration, audit search, platform overview |
| `farm` | Farms → fields → crops, with ownership-based access control; each field's irrigation system |
| `weather` | Open-Meteo forecast and recent history per farm (ET₀, rain, temperature), cached in `weather_daily`, with deterministic climatology as the offline fallback |
| `agronomy` | Pure calculation: the FAO-56 soil water balance, crop and soil parameters, FAO-33 yield response |
| `irrigation` | Recommendations per field, irrigation log (planned → completed/skipped), soil checks, pump-window scheduling, energy |
| `assets` | Water infrastructure (sources, pumps, filters, lines, in-field systems), condition checks, rule-based health scores and service forecasts |
| `maintenance` | Work orders (preventive, predictive, corrective, inspection); completing a service restarts the asset's service clock |
| `alerts` | Rules that raise, update and auto-resolve alerts after every change and every 15 minutes; one step to a work order |
| `water` | Water accounting: applied vs crop need, savings vs a calendar schedule, energy and cost, licensed allocations |
| `intelligence` | The Today dashboard (prioritised tasks) and the plain-language daily briefing |
| `demo` | Admin-only generator for a realistic demo farm |

How the models work (equations, parameters, thresholds and sources) is documented in
[docs/irrigation-and-maintenance-models.md](docs/irrigation-and-maintenance-models.md). Every
recommendation and health score carries a `source`/`modelVersion`, so trained models can replace
the rules module by module once sensor data exists, without changing the API.

Modules depend on each other through services, ports (`assets.PumpUsageProvider`,
`assets.OpenWorkOrderCounter`) and the `FarmDataChanged` event, never through each other's
tables, so there are no package cycles. The telemetry tables in `V3__telemetry_and_intelligence.sql`
are reserved for sensor ingestion.

Flyway owns the schema (`src/main/resources/db/migration`); Hibernate only validates it
(`ddl-auto: validate`), so any drift between entities and SQL fails at startup, not in production.

## Security model

| Concern | Design |
| --- | --- |
| Access token | RS256 JWT, 15 minutes. Claims: `sub`, `email`, `name`, `roles`, `sid` (session), `iss`, `aud`, `jti`. Verified statelessly: no per-request DB lookup. |
| Refresh token | Opaque 256-bit random value, stored only as a SHA-256 hash. Rotated on every refresh. Re-presenting a rotated token revokes the whole session (theft detection), with a 10 s leeway for benign client retries. 30-day sliding lifetime. |
| Web client | Sends `X-Client-Type: web`; the refresh token is set as an `HttpOnly; SameSite=Strict` cookie scoped to `/api/v1/auth` and never exposed to JavaScript. The cookie is only honoured when that header is present (CSRF defence). |
| Mobile client | Receives the refresh token in the response body and stores it in the iOS Keychain / Android Keystore. |
| Passwords | bcrypt via `DelegatingPasswordEncoder` (algorithm upgradable, hashes re-encoded at next login). NIST SP 800-63B policy: 12–72 characters, no composition rules. Unknown emails still run a hash comparison so response time doesn't reveal which accounts exist. |
| Brute force | 5 failed attempts lock the account for 15 minutes (row-locked counter). Per-IP rate limiting is applied at the edge (see the web repo's `nginx/default.conf.template`). |
| Authorisation | Roles `ADMIN` and `FARM_MANAGER`, enforced by URL rules **and** `@PreAuthorize`. Farm-scoped resources are checked top-down (farm → field → crop); inaccessible resources return 404 so ids can't be probed. |
| Admin safety | Admins cannot remove their own admin role or disable themselves; the last enabled admin cannot be removed. Disabling a user revokes all of their sessions. |
| Audit | Sign-ins (success and failure), lockouts, token reuse, password changes, session revocations, user and farm administration. Each event records actor, IP, user agent and request id. |
| Errors | RFC 9457 problem responses with a stable `code`, `requestId` and field-level `errors`. Clients branch on `code`. |

Tokens are signed with keys from `JWT_PRIVATE_KEY_LOCATION` / `JWT_PUBLIC_KEY_LOCATION`. The dev
profile generates an ephemeral key pair when none are configured (clients refresh transparently
after a restart); every other profile refuses to start without keys.

## API overview

All endpoints are under `/api/v1` and require `Authorization: Bearer <access token>` unless noted.

| Endpoint | Description |
| --- | --- |
| `POST /auth/register`, `POST /auth/login` | Public. Returns access token + session |
| `POST /auth/refresh`, `POST /auth/logout` | Public. Cookie (web) or body (mobile) refresh token |
| `GET/PATCH /me`, `POST /me/password` | Profile and password (changing it signs out other devices) |
| `GET /me/sessions`, `DELETE /me/sessions[/{id}]` | Signed-in devices; sign out one or all others |
| `GET/POST /farms`, `GET/PUT/DELETE /farms/{id}` | Own farms (admins: all farms) |
| `…/farms/{id}/fields[/{fieldId}]` | Fields of a farm |
| `…/fields/{fieldId}/crops[/{cropId}]` | Crops of a field |
| `GET/POST /admin/users`, `GET/PATCH /admin/users/{id}` | Admin: search, create, update roles/status |
| `POST /admin/users/{id}/unlock`, `DELETE /admin/users/{id}/sessions` | Admin: clear lockout, force sign-out |
| `GET /admin/audit-events` | Admin: audit search (action, outcome, actor, target, time range) |
| `GET /admin/overview` | Admin: platform counters |
| `POST /admin/demo-farm` | Admin: create the demo farm (optionally for another user) |
| `GET /farms/{id}/dashboard`, `GET /farms/{id}/briefing` | Today's tasks and KPIs (each water KPI with its previous-period value, and the last 30 days for trend lines); the plain-language briefing |
| `GET /farms/{id}/weather` | Recent and forecast weather for the farm (live or climatology, flagged) |
| `GET /farms/{id}/irrigation/status` | Water balance and recommendation for every field |
| `GET /farms/{id}/fields/{fieldId}/water-balance` | Daily balance, season stats and yield at risk for one field |
| `PUT /farms/{id}/fields/{fieldId}/irrigation-setup` | Irrigation method, application rate, efficiency, pump |
| `GET /farms/{id}/irrigation/events`, `POST …/fields/{fieldId}/irrigation/events` | Irrigation log; log or plan a run |
| `POST …/fields/{fieldId}/irrigation/plan-recommended` | Plan the recommended irrigation in one step |
| `POST …/irrigation/events/{eventId}/complete` \| `skip`, `DELETE …/irrigation/events/{eventId}` | Confirm, skip or delete a run |
| `GET/POST …/fields/{fieldId}/soil-observations` | Soil checks (feel, probe, tensiometer) that recalibrate the balance |
| `GET/POST /farms/{id}/assets`, `GET/PUT/DELETE …/assets/{assetId}`, `PATCH …/status` | Infrastructure with health and service forecast |
| `GET/POST …/assets/{assetId}/condition-checks` | Inspections: condition, flow, power, pressure, ΔP, water level, vibration, leaks |
| `GET/POST /farms/{id}/work-orders`, `PUT …/{woId}`, `POST …/{woId}/start` \| `complete` \| `cancel` \| `reopen` | Maintenance work orders |
| `GET /farms/{id}/alerts`, `POST …/evaluate`, `POST …/{alertId}/acknowledge` \| `resolve` \| `work-order` | Alerts |
| `GET /farms/{id}/water/summary?from&to` | Water accounting (defaults to the last 30 days), with `previous`: the same totals for the preceding period of equal length (null beyond the 180-day simulated history) |

## Configuration

| Variable | Default | Notes |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | none | `dev` locally, `prod` in shared environments |
| `POSTGRES_HOST` / `POSTGRES_PORT` / `POSTGRES_DB` | `localhost` / `5432` / `irrigaiq` | |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` | dev only | Required outside `dev` |
| `JWT_PRIVATE_KEY_LOCATION` / `JWT_PUBLIC_KEY_LOCATION` | none | PEM resources, e.g. `file:/run/secrets/jwt-private.pem`. Generate with `scripts/generate-jwt-keys.sh` |
| `JWT_ISSUER`, `JWT_KEY_ID` | `irrigaiq`, `irrigaiq-1` | Bump the key id when rotating keys |
| `CORS_ALLOWED_ORIGINS` | dev: 5173 and 8081 | Comma-separated browser origins |
| `REGISTRATION_ENABLED` | `true` | Set `false` to make accounts admin-provisioned only |
| `BOOTSTRAP_ADMIN_EMAIL` / `_PASSWORD` / `_NAME` | dev defaults | Used only while no admin exists |
| `WEATHER_PROVIDER` | `OPEN_METEO` | `CLIMATOLOGY` runs fully offline (tests, demos without internet) |
| `WEATHER_BASE_URL` | `https://api.open-meteo.com` | Point at a self-hosted Open-Meteo or a commercial plan |

## Testing

```bash
./mvnw test      # unit tests
./mvnw verify    # + integration tests (*IT) against a real PostgreSQL 18 via Testcontainers (needs Docker)
```

The integration tests drive the real filter chain with MockMvc: token rotation and reuse
detection, cookie-based web sessions, lockout, password change, role enforcement, admin guards,
cross-tenant farm isolation, and the operations flows end to end (a field's recommendation
responding to irrigation and soil checks; a degrading pump raising an alert that becomes a work
order whose completion resets the service clock; the dashboard, briefing, water summary and demo
farm). Unit tests cover the water-balance and asset-health engines and the weather fallback.

## Before production

- Generate RS256 keys, store the private key in a secret manager, set `JWT_*_LOCATION`.
- Run with `SPRING_PROFILES_ACTIVE=prod` behind a TLS-terminating reverse proxy (structured ECS
  logs, proxy-aware client IPs, Secure cookies, Swagger disabled).
- Use a dedicated database role for the application and a separate one for migrations.
- Follow-ups on the roadmap: email verification and password reset, MFA for administrators,
  device credentials for ESP32 ingestion, farm-level membership roles, and generating the client
  SDKs from the OpenAPI contract.
