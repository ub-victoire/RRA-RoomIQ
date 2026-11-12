# Audit Service

Audit Service consumes two approved producer paths: Notification template-audit events and Identity v1 audit-outbox events. It validates supported envelope facts, persists to the Audit-owned `AUDIT_EVENT` table, deduplicates exact event replay, and rejects conflicting event-ID reuse. S8-02 was accepted for the approved Identity producer path. S8-03 is implemented and verified, subject to production DBA provisioning.

The approved Stage 8 checklist and status table are in [Backend Documentation](../Documentation.md#stage-8-requirement-checklist-approved). S8-01 and S8-02 are accepted for their documented catalog and approved producer scope; Audit consumes Identity v1 audit-outbox events and Notification template-audit events. Other SRS actions are missing or partial as recorded in the [Audit Action-to-Owner Matrix](../docs/audit-action-owner-matrix.md); other services' business events are not automatically Audit contracts. S8-03 is implemented and verified subject to production DBA provisioning; S8-04 is implemented and verified for supported contracts; S8-05A through S8-05D are complete and full-reactor verified, with S8-05C approved and frozen. The approved search policy requires `AUDIT_READ` for global Super Admins and building-scoped Admins, with scoped access limited to assigned buildings.

The [S8-06 Implementation Readiness Register](../docs/s8-06-implementation-readiness-register.md) records proposed retention guidance, implementation evidence, decisions and operational dependencies. RLC policy registry/lifecycle APIs and a non-destructive eligibility calculator exist; this is not an approved retention policy or operational disposal mechanism. Legal/Records Management validation and formal management approval remain outstanding. Audit has no source-owner lifecycle integrations, archive, legal-hold, or disposal workflow. Production DBA role/ownership/credential provisioning and broker credentials/routing-key ACLs remain operational dependencies; documentation and tests do not prove production deployment. Existing triggers and runtime privileges do not prevent a table owner or database superuser from bypassing the controls and do not provide cryptographic tamper evidence. No live multi-service E2E evidence is claimed. S8-04 changes no approved entity columns, ERD, event contract, or API. Causation semantics remain unresolved and no causation field was added.

## API authentication and Identity authorization (S8-05B)

Audit API requests under `/api/**` require a bearer JWT. Audit validates the JWT signature with HS256 and checks its standard time claims using the shared `JWT_SECRET` configured for Identity. The request is then authorized by forwarding the original bearer token to Identity's `POST /api/v1/internal/authorization/audit-search`; Identity remains authoritative for active session/account, `AUDIT_READ`, role assignment, and building scopes. Audit does not trust request-supplied identity or authorization fields and does not access the Identity database.

The Identity response contract is `actorUserId`, `accessScope` (`GLOBAL` or `BUILDING_SCOPED`), and `buildingIds`. Audit rejects an actor mismatch, missing or malformed response fields, duplicate/null building IDs, a global decision carrying building IDs, and a scoped decision with no building IDs. The trusted decision is attached to the request and consumed by the S8-05D search handler; caller query parameters never provide authorization scope. Identity 401/403 remain denials; timeout, connection failure, unexpected client status, malformed response, and server errors fail closed as 503. Audit does not log bearer tokens or upstream response details. No Gateway route is configured.

The HS256 verification secret must be supplied to Audit through the deployment secret manager as `JWT_SECRET`, matching Identity's configured signing secret. Do not commit the value. Identity is called on each API request, so its session validation detects revoked or expired sessions beyond local JWT signature/time validation. Only the already configured health/info actuator endpoints are public; other routes require bearer authentication, and `/api/**` additionally requires the Identity decision.

The shared HS256 secret is a cross-service trust dependency: protect it as a signing secret in both deployments and rotate it in coordination with Identity. Identity's current authorization decision verifies assigned building UUIDs but does not check whether a referenced building remains active in Organization Service. This integration does not add an Audit Search route, database query, or building lifecycle lookup.

## Approved Audit Search API contract (S8-05C)

**Contract status:** Approved and frozen. The implementation status below records delivery separately and does not revise any contract decision.

### Route and filters

`GET /api/v1/audit-events` has no request body. It is an Audit Service API; no API Gateway route is included before Stage 11.

The optional query parameters are `action`, `resourceType`, `resourceId`, `actorUserId`, `outcome`, `correlationId`, `buildingId`, `occurredFrom`, `occurredTo`, `page`, `size`, `sortBy`, and `sortDirection`.

- `action`, `resourceType`, `outcome`, and `correlationId` are exact-match string filters. UUID filters `resourceId`, `actorUserId`, and `buildingId` must parse as valid UUIDs.
- `occurredFrom` and `occurredTo` accept explicit-offset ISO-8601 timestamps only. The lower bound is inclusive and the upper bound exclusive. When both are present, `occurredFrom` must be earlier than `occurredTo`.
- `buildingId` is a narrowing filter only. It cannot expand or replace the trusted scope returned by Identity.
- `page` is zero-based, defaults to `0`, and must be nonnegative. `size` defaults to `20`, must be valid, and is capped at `100`. The requested zero-based offset must fit the JPA query API's integer offset representation; an unrepresentable offset returns HTTP 400. This is a technical representation constraint, not an additional business page limit.
- `sortBy` and `sortDirection` are separate parameters. The defaults are `occurredAt` and `DESC`. Only allowlisted fields and `ASC`/`DESC` directions are accepted; the implementation adds `id` as a stable tie-breaker and never interpolates caller-supplied sort text into SQL. The allowlist is `id`, `actorUserId`, `action`, `resourceType`, `resourceId`, `buildingId`, `outcome`, `correlationId`, and `occurredAt`.
- Invalid query parameters return HTTP 400 using the shared `ApiError` envelope.

### Authorization, isolation, and errors

The API uses the validated authenticated request and the trusted Identity decision already attached by S8-05B. Identity must authorize `AUDIT_READ`. `GLOBAL` is usable only when Identity explicitly returns it with an empty building list. `BUILDING_SCOPED` requires nonempty trusted building IDs; every scoped content and count query is constrained by those IDs. A requested building outside that trusted set returns HTTP 403 `ACCESS_DENIED`. Caller-supplied actor, role, permission, and scope values never grant or expand access. Missing, malformed, or otherwise unusable decisions fail closed; Identity authorization unavailability or unusable responses return HTTP 503 using the established Identity-unavailable error contract.

Content and count queries apply identical trusted authorization and filter predicates. The database performs filtering, ordering, pagination, and counting; filter values are parameterized and sort fields are mapped through a fixed allowlist. No direct-ID lookup is part of this contract.

Use the shared `ApiError` envelope for HTTP 400 invalid query parameters, 401 missing/invalid authentication, 403 authorization denial or an out-of-scope building filter, and 503 Identity authorization unavailability or unusable responses. Errors must not expose SQL, stack traces, bearer tokens, raw Identity responses, or sensitive query details.

**S8-05D-02 reconciliation:** an HTTP 200 `BUILDING_SCOPED` decision with an empty `buildingIds` list is treated as an unusable Identity response and returns 503 `IDENTITY_AUTHORIZATION_UNAVAILABLE`. Explicit Identity 401/403 denials retain their statuses. Identity behavior is unchanged.

### Response and search-access logging

Success uses `ApiResponse<AuditEventSearchPageResponse>`. Page fields are `content`, `page`, `size`, `totalElements`, `totalPages`, `sortBy`, and `sortDirection`. Each item contains only `id`, `actorUserId`, `action`, `resourceType`, `resourceId`, `buildingId`, `outcome`, `correlationId`, and `occurredAt`; `buildingId` maps to the persisted `scopeOfficeBuildingId`. Do not expose raw metadata or add a `sourceService` column.

Search access is recorded in operational structured logs only. Do not create Audit events for successful or denied searches, introduce a new Audit event category or producer, or log bearer tokens or sensitive/unapproved query values. Preserve correlation IDs.

The Backend Requirements Specification §10.2 says “Audit sensitive administrative access” but does not specify whether read/search access must be an Audit event. The approved S8-05C decision is operational structured logging only for Audit searches; this does not change or interpret that broader requirement for other administrative access.

`AUDIT_READ` remains cataloged but is not automatically granted to Admin or Super Admin roles. Any role-permission assignment must be explicit and performed by an authorized Super Admin.

### Persistence and gateway boundary

This contract requires no schema, ERD, migration, or index change. Search and count use the existing Audit-owned data only; no cross-service database access is permitted. No API Gateway route is added before Stage 11.

## Audit Search API implementation (S8-05D)

`GET /api/v1/audit-events` is implemented in Audit Service with no request body. It consumes only the trusted authorization decision attached by `AuditIdentityAuthorizationFilter`, delegates query validation/search to the S8-05D persistence service, and returns `ApiResponse<AuditEventSearchPageResponse>`. Public item mapping is explicit and excludes raw metadata and persistence-only fields. The OpenAPI operation documents all filters, defaults, bounds, offset time semantics, sorting allowlist, bearer authorization, response DTO, and 400/401/403/503 errors.

Malformed query parameters return the shared `ApiError` envelope with HTTP 400. Search logging is operational and excludes bearer tokens and filter values. The validated request correlation ID is forwarded to Identity and returned to the caller. The representable database offset bound is enforced without an arbitrary page-number cap.

**S8-05 status: COMPLETE** — S8-05A authorization is implemented and verified; S8-05B is accepted; S8-05C is approved and frozen; S8-05D-01/02/03/04A are implemented and regression-verified. Identity derives the actor from the authenticated JWT and requires the explicit persisted `AUDIT_READ` permission: active global Super Admin assignments yield `GLOBAL`, while active Admin assignments yield only their persisted building scopes. `AUDIT_READ` is cataloged but not automatically granted to any role.

Final regression used the existing Maven wrapper from `backend/`:

- `./identity-service/mvnw -f pom.xml -pl identity-service -am test` — 76 Identity tests and 6 common-web tests; 0 failures, errors, or skips.
- `./identity-service/mvnw -f pom.xml -pl audit-service -am test` — 57 Audit tests and 6 common-web tests; 0 failures, errors, or skips.
- `./identity-service/mvnw -f pom.xml -pl common-web test` — 6 tests; 0 failures, errors, or skips.
- `./identity-service/mvnw -f pom.xml test` — all 11 backend reactor modules passed, 509 tests; 0 failures, errors, or skips.

The final regression includes tests for trusted scope, denials, invalid decisions, query validation, pagination/sorting, response field privacy, correlation ID propagation, Identity error redaction, and nullable response serialization. This status is not a production deployment claim or formal RRA acceptance. S8-06 is the next stage and remains blocked pending RRA retention, archival, legal-hold, and disposal policy approval; no S8-06 implementation has started. No Gateway route, permission assignment, schema, migration, or ERD change was made during final verification.

## Notification template audit consumer

Notification persists a safe audit event to its own transactional outbox with the template mutation. The Notification relay publishes persistent messages on the `roomiq.events.v1` exchange with routing key `audit.notification`, sets the broker message ID to the outbox event ID, and marks an outbox row published only after a positive RabbitMQ publisher confirmation and successful routing. Audit Service consumes the durable `roomiq.audit.notification.v1` queue, requires that broker message ID and rejects missing or mismatched values. It validates the exact envelope, schema version, event type, source, resource, correlation format, and metadata fields before persistence. Correlation may be null; malformed non-null values are rejected using Identity's `[A-Za-z0-9._-]{1,128}` rule.

`AUDIT_EVENT.id` is the event ID, making exact duplicate delivery idempotent. Conflicting reuse of an event ID is rejected. Notification metadata retains the validated `sourceService` and `schemaVersion` using the Identity convention (`sourceService`, `schemaVersion`, and nested `eventMetadata`) while preserving the original allowlisted template code/version/channel/changed-field facts. The producer's original occurrence timestamp and trusted actor/resource facts are persisted; this event has no building scope, so scope is null, and its outcome is `SUCCESS`. Correlation remains nullable and is stored as supplied when valid. Payload actor/source fields are event facts, not authentication or authorization; the consumer does not independently authenticate them.

The processor is transactional and the listener returns after processing; the listener is configured for automatic acknowledgement and bounded retry, with invalid/unsupported messages routed to the durable `roomiq.audit.notification.dlx.v1` exchange and `roomiq.audit.notification.dead-letter.v1` queue. The insert-only repository interface, Hibernate `@Immutable`, PostgreSQL UPDATE/DELETE/TRUNCATE triggers, and restricted runtime role provide layered append-only protection. This does not protect against a table owner or superuser, who can alter privileges or disable triggers, and it is not cryptographic tamper evidence. Audit stores actor IDs as identifiers only and never reads the Notification database. Template subject/body content is intentionally excluded from audit metadata.

## Identity audit consumer

Identity writes its version-1 security/administration envelope to its own transactional `identity_audit_outbox`; the relay publishes to `audit.identity`. Audit consumes the durable `roomiq.audit.identity.v1` queue, validates the supported Identity event type/version/source and safe metadata, then commits the deduplicated record to the Audit-owned database. Audit does not access the Identity database. The stable event ID is the deduplication key; conflicting reuse is rejected. Producer actor/source fields are trusted event facts from the owner-service path, not authentication supplied by RabbitMQ payload. Identity's current audit envelope does not carry causation; no causation value is fabricated. The approved Audit schema has correlation but no causation or dedicated source-service column.

## Append-only storage protections (S8-03)

- The repository declares only `findById`, `count`, and `insertIfAbsent`; it does not inherit Spring Data save/delete methods.
- `AuditEvent` is annotated with Hibernate `@Immutable`.
- Flyway V2 installs PostgreSQL triggers that reject row UPDATE/DELETE and statement TRUNCATE.
- The runtime DB role receives only schema USAGE and table SELECT/INSERT. Flyway uses a separate migration role.

These controls were tested using separate PostgreSQL test principals. They prevent ordinary application/runtime-role mutation but do not protect against the table owner or a database superuser, who can alter privileges or disable triggers. They are not cryptographic tamper evidence.

## Local configuration

Configure PostgreSQL and RabbitMQ outside source control:

```dotenv
AUDIT_DB_URL=jdbc:postgresql://localhost:5432/roomiq_audit
AUDIT_DB_USERNAME=roomiq_audit_app
AUDIT_DB_PASSWORD=replace-with-runtime-secret
AUDIT_DB_MIGRATION_USERNAME=roomiq_audit_migrator
AUDIT_DB_MIGRATION_PASSWORD=replace-with-migration-secret
RABBITMQ_HOST=localhost
RABBITMQ_PORT=5672
AUDIT_RABBITMQ_USERNAME=replace-with-audit-account
AUDIT_RABBITMQ_PASSWORD=replace-with-local-secret
IDENTITY_SERVICE_URL=http://localhost:8081
JWT_SECRET=use-the-same-secret-as-identity-from-your-secret-manager
AUDIT_IDENTITY_CONNECT_TIMEOUT=2s
AUDIT_IDENTITY_READ_TIMEOUT=5s
AUDIT_EVENT_MAX_ATTEMPTS=3
AUDIT_EVENT_RETRY_INITIAL_INTERVAL=1s
AUDIT_EVENT_RETRY_MULTIPLIER=2
AUDIT_EVENT_RETRY_MAX_INTERVAL=5s
```

Before deployment, a DBA must provision these separate principals and secrets. The migrator must own `audit_event` and have the schema privileges required by Flyway. The runtime principal must be a non-owner with no inherited write privileges; Flyway V2 grants it only SELECT and INSERT on `audit_event` (plus schema USAGE). Production credentials are not created by migrations and must be delivered through the deployment secret manager. The migration fails if `roomiq_audit_app` has not been provisioned.

Provide a service-specific broker account restricted to the required exchange, queue, and dead-letter topology in deployed environments. The local shared/guest account defaults are for development only; never commit real credentials.

## Verification

From the repository root:

```bash
mvn -f backend/pom.xml -pl audit-service -am test
```

The prior Stage 7 Notification template-audit integration evidence is recorded in [Backend Documentation](../Documentation.md#stage-7-requirement-checklist-approved). It is not Stage 8 acceptance evidence. The Stage 8 item-by-item scope and gates are in [Backend Documentation](../Documentation.md#stage-8-requirement-checklist-approved).

Available Surefire reports dated 2026-11-05 show 15 Audit suites / 59 tests with zero failures, errors, or skips; they do not prove a fresh run after later commits. The historical 11-module full-reactor reports show 563 tests with zero failures, errors, or skips, but do not verify live multi-service E2E or unimplemented retention workflows. Booking's mocked-owner integration test is not a live cross-service flow.
