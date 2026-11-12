# Records Lifecycle Control Service

RLC implements a policy-version/rule registry and policy lifecycle management APIs, transactional lifecycle-event evidence, a persistent legal-hold registry, and a non-destructive retention eligibility calculation. It receives provisionally integrated Booking request evidence over a protected internal service boundary; no other source-owner integration is implemented. RLC does not execute disposal or archive transfers, and its legal-hold registry does not enforce holds in source-owner systems. Eligibility is not disposal authorization.

## Current implementation and limits

The policy API supports draft create/get, rule create/update, validation, activation, supersession, and withdrawal. Policy writes and corresponding lifecycle-control events share a database transaction; database guards require matching lifecycle evidence for policy mutations, and lifecycle-control events are append-only for the runtime role. Policy-management requests use bearer authentication and fail-closed authorization through Identity. Identity requires an active global Super Admin with the explicit `RETENTION_POLICY_MANAGE` permission; the permission is not automatically granted.

The calculator selects the effective policy for the supplied authoritative start time, applies matching rules for one supplied category, uses the longest expiry among those rules, and treats expiry equality as eligible. It fails closed for missing or conflicting policies, missing category/start, unknown or unacknowledged owner, missing statutory schedule results, and unavailable or blocking hold state. A source-aware evaluator uses the latest accepted RLC receipt, requires an exact owner-acknowledged revision, and rejects receipts older than the configurable freshness limit. There is no category classifier, operational hold registry, or cross-category longest-applicable calculation. No calculation result authorizes archive or disposal.

The retention schedule remains proposed. Formal RRA Legal/Records Management validation and management approval are not recorded. The S8-06A register has 36 open decisions with blank approval fields. Documentation cites a CTO S8-06C implementation-baseline instruction, but no signed instruction is attached to the repository; an implementation baseline is not formal policy approval, technical readiness, or operational readiness. HOLD-01 through HOLD-05 remain unresolved. The registry is a provisional RLC technical control, not an operationally approved hold process. S8-06D and S8-06F have no authoritative definitions in the repository. ARC-08 and DSP-06 criteria have no corresponding approval-package rows.

Only Booking `booking_request` evidence is integrated. Archive transfer/verification/recovery, source-owner hold acknowledgement/enforcement, and disposal clearance/authorization/execution/certificates/reconciliation are not implemented. Audit remains append-only and no Audit retention/disposal mechanism exists. S8-06 remains blocked; S8-07 and Stage 8 acceptance remain blocked.

### Provisional Booking source evidence integration

The internal endpoint `POST /internal/v1/source-evidence/booking-requests` accepts contract v1 only under an RS256 token with a pinned issuer/key, exact audience `rra-roomiq-records-lifecycle-source-evidence`, subject `booking-service`, and signed `BOOKING_SOURCE_EVIDENCE_PUBLISH` permission. A request-body filter limits JSON payloads to 64 KiB. It is not Gateway-routed. Contract fields and behavior are documented in [the source evidence contract note](../docs/source-owner-evidence-contract-v1.md).

Booking remains authoritative for `booking_request`; it records source revisions and evidence in a Booking-owned transactional outbox and sends snapshots synchronously to RLC. Category and start-basis mappings are explicit and empty by default; RLC separately requires a configured category allow-list, also empty by default. Receipt outcomes and evidence digests are append-only under the RLC runtime role. The evaluator's source-aware entry point rejects missing evidence, unresolved conflicts, stale receipts (default maximum age `PT24H`), and nonmatching acknowledged revisions. The full contract, key settings, mapping behavior, delivery acknowledgement, retry/reconciliation behavior, and limitations are documented in the linked contract note.

The Booking service token follows the existing locally signed service-token pattern. The dedicated permission is registered in Identity's permission catalog, but Booking signs its own permission claim; no Identity-issued token or runtime permission lookup is performed during evidence delivery. This is a project-provisional implementation, not RRA approval, formal source-owner acceptance, or production workload identity approval. The append-only RLC receipt is not an Audit Service event and does not replace existing approved Audit feeds. No other source-owner integrations, production key provisioning/rotation, externally monitored reconciliation, archive, hold, or disposal functionality is established.

### Versioned evaluation and history

The source-aware evaluator uses the latest accepted RLC receipt only when the caller's acknowledged source revision exactly matches it, no unresolved same-or-newer conflict exists, and the server receipt time is within the configured maximum age. The evidence's explicitly mapped category and authoritative start are used. Policy rule start-basis codes must match the source evidence basis; a mismatch leaves the result `NOT_ELIGIBLE` (`RULE_START_MISSING`) rather than falling back to another timestamp in the payload.

The existing policy registry remains the sole policy store. Evaluation selects the unique `APPROVED` or `SUPERSEDED` version effective at the source's authoritative start instant (`effectiveFrom` inclusive, `effectiveTo` exclusive). A withdrawn-only or draft-only match, overlap, missing category/rule/start, or unavailable statutory schedule fails closed. `YEAR` uses UTC calendar-year arithmetic (including calendar leap-day clamping); `DAY` uses UTC calendar days. Equality with `retainUntil` is eligible. `Africa/Kigali` is a display convention; persisted/calculated instants remain UTC and this service does not convert them to Kigali wall time.

Migration V8 adds RLC-local append-only evaluation history. Each attempt stores the evidence receipt/event/revision/digest and receipt time, acknowledged revision, evaluation inputs, candidate policy-version metadata, candidate rule snapshots, and result in relational columns plus a JSONB provenance snapshot. The service returns an evaluation ID; repeated evaluations create distinct history rows with the same deterministic result when inputs are unchanged. The history table has no runtime UPDATE/DELETE/TRUNCATE permission and database triggers reject mutation. This history is not disposal authorization or an Audit Service event.

Booking request types `SECRETARY_REQUEST` and `ADMIN_DIRECT_BOOKING` require separately configured category and supported start-basis mappings. Booking evidence delivery is disabled by default and its mapping is empty; RLC's accepted-category list is also empty by default. Thus no production Booking category mapping or policy is enabled by repository defaults. Tests use the clearly labelled `TEST_BOOKING_CATEGORY`, `RECORD_CREATED_AT`, and `SYNTHETIC_TEST_ONLY` policy metadata solely to verify behavior. No statutory period is encoded or recommended here.

### Provisional persistent legal-hold registry (WP4)

Flyway V9 adds an RLC-owned `lifecycle_legal_hold` current-state table. The authenticated service-level API is under `/api/v1/legal-holds/**`; it is not routed through the API Gateway. Every request is authorized by Identity using the current active user's global `SUPER_ADMIN` assignment and explicit `LEGAL_HOLD_MANAGE` permission. Identity V8 catalogs that permission but deliberately assigns it to no role. RLC derives the actor only from Identity's response; request payloads have no trusted actor, role, permission, or scope fields.

Supported scopes are exact `RECORD` (`sourceService`, `recordType`, `recordId`) and `CATEGORY` (`categoryCode`). Supported transitions are `DRAFT → PENDING_APPROVAL → ACTIVE` or `REJECTED`, `ACTIVE → RELEASE_PENDING → RELEASED`, and active-to-active amendments. Numeric `If-Match` versions, transaction-scoped advisory locks, row locking, and `Idempotency-Key` protect transitions and retries. Initiators cannot approve, request release, or release their own hold; a release requester cannot approve that release. Amendments increment version and retain before/after snapshots in lifecycle history. Overlapping holds remain independent.

Flyway V9 records each successful lifecycle change in the existing append-only `lifecycle_control_event` table and installs database mutation guards requiring matching audit evidence for hold-state changes. Hold evaluation locks the exact record and category scope before reading matching `PENDING_APPROVAL`, `ACTIVE`, and `RELEASE_PENDING` rows. Any matching hold blocks eligibility; unavailable hold-state lookup fails closed. Evaluation history captures matching hold IDs, references, versions, scopes, and statuses. Releasing one hold does not release another. A release-verification reference is only recorded evidence; this integration does not contact Booking or another source owner and does not prove that a hold was enforced in source data, archive copies, or backups.

The registry is limited to technical status, authorization and non-destructive eligibility blocking. Its permission is not provisioned for production, the scope taxonomy and workflow remain pending RRA review, and no real legal references or statutory periods are introduced. It does not implement source-owner acknowledgement, archive transfer, disposal clearance or execution, deletion, or a Gateway route.

| Method and path | Behavior |
|---|---|
| `POST /api/v1/legal-holds` | Create a `DRAFT`; requires an `Idempotency-Key`. |
| `GET /api/v1/legal-holds/{holdId}` | Read the current RLC registry version. |
| `POST /api/v1/legal-holds/{holdId}/submit` | Initiator submits a draft; requires numeric `If-Match` version and `Idempotency-Key`. |
| `POST /api/v1/legal-holds/{holdId}/approve` or `/reject` | A different authorized actor records the decision and required evidence reference. |
| `PUT /api/v1/legal-holds/{holdId}/amend` | Amend an active hold's reason/scope with a new version and evidence reference. |
| `POST /api/v1/legal-holds/{holdId}/request-release` | A non-initiator requests release with a required evidence reference. |
| `POST /api/v1/legal-holds/{holdId}/release` | A different actor approves release and records its verification reference. |

Every mutating transition except creation requires both numeric `If-Match` and `Idempotency-Key` headers. The API returns the current hold status and version; stale versions conflict rather than overwriting later state. The migration enforces valid scopes/status transitions, version increments, matching event-backed state mutation, and rejects update/delete/truncate of lifecycle history plus delete/truncate of hold state.

WP4 verification on 2026-11-12: `mvn -f backend/pom.xml -pl records-lifecycle-service -am -Dtest=RetentionPolicyEvaluationServiceTests,SourceOwnerEvidencePostgresTests -Dsurefire.failIfNoSpecifiedTests=false test` passed 21 tests, zero failures/errors/skips (exit 0), including PostgreSQL lifecycle, concurrency, append-only, overlap, and evaluation cases. `mvn -f backend/pom.xml -pl identity-service -am -Dtest=OrganizationAuthorizationControllerTests -Dsurefire.failIfNoSpecifiedTests=false test` passed 10 tests, zero failures/errors/skips (exit 0). The complete backend command `mvn -f backend/pom.xml test` passed all 12 reactor projects and 614 tests with zero failures/errors/skips (exit 0), including the registry-outage fail-closed test. These are automated tests only, not production/RRA approval or deployment evidence.

WP3 baseline verification before WP4 passed the Booking source-evidence relay/PostgreSQL tests (8 tests) and RLC source-evidence, validator, calculator, policy-evaluation, and policy-registry PostgreSQL tests (57 tests), 65 total, with zero failures, errors, or skips. The earlier full backend reactor passed 11 modules / 106 suites / 608 tests. Those results are historical WP3 evidence, not WP4 verification.

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
