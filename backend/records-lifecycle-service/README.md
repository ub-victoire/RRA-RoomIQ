# Records Lifecycle Control Service

RLC implements a policy-version/rule registry and policy lifecycle management APIs, transactional lifecycle-event evidence, a non-destructive retention eligibility calculation, and a bounded provisional legal-hold registry. It receives provisionally integrated Booking request evidence over a protected internal service boundary; no other source-owner integration is implemented. RLC does not execute disposal or archive transfers. Eligibility is not disposal authorization.

## Current implementation and limits

The policy API supports draft create/get, rule create/update, validation, activation, supersession, and withdrawal. Policy writes and corresponding lifecycle-control events share a database transaction; database guards require matching lifecycle evidence for policy mutations, and lifecycle-control events are append-only for the runtime role. Policy-management requests use bearer authentication and fail-closed authorization through Identity. Identity requires an active global Super Admin with the explicit `RETENTION_POLICY_MANAGE` permission; the permission is not automatically granted.

The calculator selects the effective policy for the supplied authoritative start time, applies matching rules for one supplied category, uses the longest expiry among those rules, and treats expiry equality as eligible. It fails closed for missing or conflicting policies, missing category/start, unknown or unacknowledged owner, missing statutory schedule results, and unavailable or blocking hold state. A source-aware evaluator uses the latest accepted RLC receipt, requires an exact owner-acknowledged revision, and rejects receipts older than the configurable freshness limit. There is no category classifier, RRA-approved/operational hold authority, or cross-category longest-applicable calculation. No calculation result authorizes archive or disposal.

The retention schedule remains proposed. Formal RRA Legal/Records Management validation and management approval are not recorded. The S8-06A register has 36 open decisions with blank approval fields. Documentation cites a CTO S8-06C implementation-baseline instruction, but no signed instruction is attached to the repository; an implementation baseline is not formal policy approval, technical readiness, or operational readiness. HOLD-01 through HOLD-05 remain unresolved. S8-06D and S8-06F have no authoritative definitions in the repository. ARC-08 and DSP-06 criteria have no corresponding approval-package rows.

Only Booking `booking_request` evidence is integrated. WP5 adds bounded Booking-side technical hold enforcement, not an approved operational hold regime. Archive transfer/verification/recovery and disposal clearance/authorization/execution/certificates/reconciliation are not implemented. Audit remains append-only and no Audit retention/disposal mechanism exists. S8-06 remains blocked; S8-07 and Stage 8 acceptance remain blocked.

### Provisional Booking source evidence integration

The internal endpoint `POST /internal/v1/source-evidence/booking-requests` accepts contract v1 only under an RS256 token with a pinned issuer/key, exact audience `rra-roomiq-records-lifecycle-source-evidence`, subject `booking-service`, and signed `BOOKING_SOURCE_EVIDENCE_PUBLISH` permission. A request-body filter limits JSON payloads to 64 KiB. It is not Gateway-routed. Contract fields and behavior are documented in [the source evidence contract note](../docs/source-owner-evidence-contract-v1.md).

Booking remains authoritative for `booking_request`; it records source revisions and evidence in a Booking-owned transactional outbox and sends snapshots synchronously to RLC. Category and start-basis mappings are explicit and empty by default; RLC separately requires a configured category allow-list, also empty by default. Receipt outcomes and evidence digests are append-only under the RLC runtime role. The evaluator's source-aware entry point rejects missing evidence, unresolved conflicts, stale receipts (default maximum age `PT24H`), and nonmatching acknowledged revisions. The full contract, key settings, mapping behavior, delivery acknowledgement, retry/reconciliation behavior, and limitations are documented in the linked contract note.

The Booking service token follows the existing locally signed service-token pattern. The dedicated permission is registered in Identity's permission catalog, but Booking signs its own permission claim; no Identity-issued token or runtime permission lookup is performed during evidence delivery. This is a project-provisional implementation, not RRA approval, formal source-owner acceptance, or production workload identity approval. The append-only RLC receipt is not an Audit Service event and does not replace existing approved Audit feeds. No other source-owner integrations or production key provisioning/rotation or externally monitored reconciliation are established.

### Versioned evaluation and history

The source-aware evaluator uses the latest accepted RLC receipt only when the caller's acknowledged source revision exactly matches it, no unresolved same-or-newer conflict exists, and the server receipt time is within the configured maximum age. The evidence's explicitly mapped category and authoritative start are used. Policy rule start-basis codes must match the source evidence basis; a mismatch leaves the result `NOT_ELIGIBLE` (`RULE_START_MISSING`) rather than falling back to another timestamp in the payload.

The existing policy registry remains the sole policy store. Evaluation selects the unique `APPROVED` or `SUPERSEDED` version effective at the source's authoritative start instant (`effectiveFrom` inclusive, `effectiveTo` exclusive). A withdrawn-only or draft-only match, overlap, missing category/rule/start, or unavailable statutory schedule fails closed. `YEAR` uses UTC calendar-year arithmetic (including calendar leap-day clamping); `DAY` uses UTC calendar days. Equality with `retainUntil` is eligible. `Africa/Kigali` is a display convention; persisted/calculated instants remain UTC and this service does not convert them to Kigali wall time.

Migration V8 adds RLC-local append-only evaluation history. Each attempt stores the evidence receipt/event/revision/digest and receipt time, acknowledged revision, evaluation inputs, candidate policy-version metadata, candidate rule snapshots, and result in relational columns plus a JSONB provenance snapshot. The service returns an evaluation ID; repeated evaluations create distinct history rows with the same deterministic result when inputs are unchanged. The history table has no runtime UPDATE/DELETE/TRUNCATE permission and database triggers reject mutation. This history is not disposal authorization or an Audit Service event.

Booking request types `SECRETARY_REQUEST` and `ADMIN_DIRECT_BOOKING` require separately configured category and supported start-basis mappings. Booking evidence delivery is disabled by default and its mapping is empty; RLC's accepted-category list is also empty by default. Thus no production Booking category mapping or policy is enabled by repository defaults. Tests use the clearly labelled `TEST_BOOKING_CATEGORY`, `RECORD_CREATED_AT`, and `SYNTHETIC_TEST_ONLY` policy metadata solely to verify behavior. No statutory period is encoded or recommended here.

Focused WP3 verification on 2026-11-12 passed the Booking source-evidence relay/PostgreSQL tests (8 tests) and RLC source-evidence, validator, calculator, policy-evaluation, and policy-registry PostgreSQL tests (57 tests), 65 total, with zero failures, errors, or skips. The WP3 full backend reactor passed 11 Maven modules / 106 test suites / 608 tests, zero failures/errors/skips (exit code 0). WP4 focused verification passed 29 tests (10 Identity authorization, 18 RLC PostgreSQL, 1 evaluator), and the WP4 full backend reactor passed 11 modules / 106 suites / 612 tests, with zero failures, errors, or skips (exit code 0). These automated tests do not establish production policy approval, production mappings/keys, a deployed Booking-to-RLC E2E, operational hold authority/owner enforcement, or archive/disposal readiness.

### WP4 provisional legal-hold registry

The bounded RLC registry covers only `booking-service` / `BOOKING_REQUEST`; it does not claim authority over other Booking aggregates, source owners, archive copies, or backups. The internal RLC API is `POST /api/v1/legal-holds`, `GET /api/v1/legal-holds/{holdId}`, `POST /api/v1/legal-holds/{holdId}/release-requests`, and `POST /api/v1/legal-holds/{holdId}/release-verification`. It is not Gateway-routed. Identity authorizes each operation for an active, unscoped `SUPER_ADMIN` holding `RECORDS_HOLD_MANAGE`; Identity derives the actor from the validated bearer-token subject and current permission state. The permission is cataloged by Identity Flyway V9 and is not assigned automatically. RLC limits JSON request bodies and rejects unauthenticated/unauthorized calls.

A created hold begins `PENDING_BLOCK`; a further unresolved hold on the same record is `CONFLICTED`. Both and `RELEASE_PENDING` and `RELEASE_ENFORCEMENT_PENDING` block eligibility. Each release requires independent verification and a matching durable Booking release acknowledgement before it becomes `RELEASED`. Another unresolved hold remains blocking. Requests are idempotent and hold changes append lifecycle-control events in the same RLC PostgreSQL transaction. Flyway V10 adds the RLC-local hold table, event vocabulary, mutation guards and runtime grants; untracked updates and deletion/truncation are rejected. Evaluation serializes with evidence/hold mutations and persists hold IDs, states, versions and verification facts in its history provenance.

WP5 sends ordered instructions through the RLC V11 outbox to Booking over HTTPS. A matching durable Booking acknowledgement, not HTTP success alone, records source enforcement; before that acknowledgement, `PENDING_BLOCK` does not prove Booking protection. Booking locally guards the scoped request and related lifecycle operations. Shared fixture checks and both real signer-to-counterpart-verifier paths now pass in a local test-only Maven module; the focused Booking/RLC/contract selection passed 40 tests, the Identity suite passed 80 (common-web 6), and the final full reactor passed 626 tests across 12 modules with zero failures/errors/skips. This is not live deployed interoperability. Existing pre-outbox holds, reconciliation and monitoring, formal authority/delegation, RRA validation, production identity and TLS provisioning, archive/backup coverage, and production operation remain outstanding. A hold registry operation does not authorize disposal or activate a retention policy. See [the source-owner contract](../docs/source-owner-evidence-contract-v1.md).

## Runtime configuration

The service requires the following externally provisioned settings:

| Variable | Purpose |
|---|---|
| `RLC_DB_URL` | PostgreSQL JDBC URL for the RLC database |
| `RLC_DB_USERNAME` / `RLC_DB_PASSWORD` | Runtime principal `roomiq_rlc_app` |
| `RLC_DB_MIGRATION_USERNAME` / `RLC_DB_MIGRATION_PASSWORD` | Flyway principal `roomiq_rlc_migrator` |
| `IDENTITY_SERVICE_URL` | Base URL for the Identity authorization API |
| `JWT_SECRET` | Shared HS256 signing/verification key; at least 32 bytes |
| `BOOKING_SERVICE_TOKEN_ISSUER` | Expected issuer for Booking's RS256 source-evidence token |
| `BOOKING_SOURCE_EVIDENCE_CURRENT_KEY_ID` / `BOOKING_SOURCE_EVIDENCE_CURRENT_PUBLIC_KEY_LOCATION` | Current pinned Booking verification key |
| `BOOKING_SOURCE_EVIDENCE_PREVIOUS_KEY_ID` / `BOOKING_SOURCE_EVIDENCE_PREVIOUS_PUBLIC_KEY_LOCATION` | Optional previous key during rotation |
| `RLC_BOOKING_SOURCE_EVIDENCE_ALLOWED_CATEGORIES` | Comma-separated Booking evidence category allow-list; defaults empty |
| `RLC_SOURCE_EVIDENCE_MAX_AGE` | Maximum age for source evidence to remain eligible; defaults to `PT24H` |
| `RLC_BOOKING_HOLD_SERVICE_URL` | HTTPS Booking base URL for hold instructions; Compose maps this to `BOOKING_SERVICE_URL` in the RLC container |
| `BOOKING_SERVICE_TOKEN_ISSUER` and Booking source-evidence current/previous key ID/public-key locations | Trusted RS256 Booking identity for evidence ingestion |
| `RLC_SERVICE_TOKEN_CURRENT_KEY_ID` / `RLC_SERVICE_TOKEN_CURRENT_PUBLIC_KEY_LOCATION` and optional previous-key pair | RLC verification keys that Booking uses to authenticate hold instructions |
| `RLC_SERVICE_TOKEN_ISSUER` / `RLC_SERVICE_TOKEN_KEY_ID` / `RLC_SERVICE_TOKEN_PRIVATE_KEY_LOCATION` | RLC hold sender issuer, key ID and private key; provision externally |
| `RLC_BOOKING_HOLD_DELIVERY_ENABLED` | Scheduler enabled flag; defaults true but delivery does not start without complete sender configuration |

The `applications` Compose profile now includes both Booking and RLC and passes these settings through. Its `.env.example` contains placeholders only: evidence delivery and hold delivery default disabled, the category allow-list and public-key locations remain empty, and `RLC_BOOKING_HOLD_SERVICE_URL` must be a provisioned HTTPS endpoint. This dedicated setting avoids colliding with `BOOKING_SERVICE_URL`, which other local services use for their ordinary HTTP calls. The profiled Booking container listens on HTTP; use a separately managed HTTPS ingress/proxy for hold instructions rather than targeting that listener directly. Compose configuration does not provision databases, TLS, trust keys, RRA-approved mappings, or policy.

`IDENTITY_CONNECT_TIMEOUT` and `IDENTITY_READ_TIMEOUT` default to 2 and 5 seconds. `RLC_SERVICE_PORT` defaults to 8089. Production (`prod` or `production` Spring profile) requires an HTTPS Identity URL. Identity authorization is fail-closed: a denial returns 403, while an unavailable Identity service returns 503 and does not permit policy writes.

Liveness and readiness are exposed at `/livez` and `/readyz`; readiness includes the application readiness state and PostgreSQL connectivity. Identity is not probed without an authenticated request, so the readiness endpoint does not assert that Identity authorization is currently reachable.

## Database provisioning

Before Flyway starts, the DBA must create separate, non-superuser principals named `roomiq_rlc_migrator` and `roomiq_rlc_app`, provision their credentials through the approved secret manager, and grant the migrator the required schema migration privileges. The migrator must own the RLC tables/functions; the runtime principal must not own them and must not inherit owner, migration, or superuser privileges. Flyway V5 grants the runtime principal SELECT/INSERT on lifecycle events and SELECT/INSERT/UPDATE on policy tables. It grants no event UPDATE/DELETE/TRUNCATE or policy DELETE/TRUNCATE privileges; policy mutation triggers still require a matching lifecycle event in the same transaction.

The migration intentionally fails if either role has not been provisioned. Migrations do not create production accounts or credentials. Existing installations must have the DBA verify/transfer object ownership to the migration principal before applying new migrations. V5 grants the runtime principal SELECT/INSERT on lifecycle events and SELECT/INSERT/UPDATE on policy tables; V6 creates append-only source-evidence receipts with SELECT/INSERT runtime privileges; V7 adds Booking lifecycle status and observation time to those receipts. The database owner and superusers remain privileged and can alter triggers; deployment evidence must record and control that residual privilege. Passing Testcontainers role/trigger tests does not establish production provisioning.

## Container

Build from the backend directory with:

```sh
docker build -f records-lifecycle-service/Dockerfile -t roomiq-records-lifecycle-service .
```

The service can be enabled in the local infrastructure Compose file with the `applications` profile. The Compose deployment requires the same environment variables listed above; do not put production secrets in the Compose file or repository. Database and Identity hostnames, TLS termination, secret delivery, database roles and operational monitoring must be provisioned by RRA operations.
