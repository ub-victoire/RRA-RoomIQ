# RLC Source-Owner Evidence Contract v1 — Booking Integration and Evaluation Controls

## Status and scope

This note documents the implemented technical contract and Booking-to-RLC integration for S8-06 Work Package 2, the WP3 evaluator path, and the WP4/WP5 hold-control boundaries. The integration follows the provisional project baseline and is **pending RRA review — not yet validated**. It is not formal RRA approval, legal validation, records-management approval, production identity approval, or policy activation.

Booking is the authoritative owner for `booking_request` records and this first integration covers only that aggregate. It does not cover reservations, meetings, participants, extensions, or other Booking records. Booking sends evidence to RLC over a service-authenticated internal HTTP endpoint. Neither service accesses the other's database.

## Internal v1 evidence fields

The Java record is `SourceOwnerEvidence`. Its implemented fields are:

| Field | Type | Requirement and meaning |
|---|---|---|
| `contractVersion` | integer | Must equal `1`; unknown versions are rejected. |
| `sourceService` | string | Must be `booking-service` and equal the subject authenticated by the RLC RS256 resource-server boundary. |
| `recordType` | string | `BOOKING_REQUEST` for this integration. |
| `recordId` | string | Stable UUID string from Booking's `booking_request.id`. |
| `eventId` | UUID | Stable event identity used for duplicate and event-reuse detection. |
| `eventType` | string | Uppercase owner event code. |
| `eventVersion` | positive integer | Source event schema/version. |
| `sourceRevision` | positive integer | Monotonic per-record revision used to order source changes. The owner must define and guarantee this property before integration. |
| `categoryCode` | string | Uppercase configured category code. Booking's request-type mapping and RLC's independent allow-list must both explicitly include the value; both default to empty/unconfigured. |
| `recordCreatedAt` | timestamp | Required source record creation time. |
| `occurredAt` | timestamp | Required source event occurrence time. |
| `authoritativeStartAt` | timestamp | Required start instant selected by the source under the declared `startBasisCode`. |
| `startBasisCode` | string | Required uppercase start-basis code. Implemented consistency checks cover record creation, occurrence/original-event, closure, and later-of-decision/transaction-closure. |
| `closedAt` | timestamp or null | Source closure time where applicable; cannot precede creation. |
| `decisionAt` | timestamp or null | Source decision time for the later-of-decision/closure basis. |
| `transactionClosedAt` | timestamp or null | Source transaction-closure time for the later-of-decision/closure basis. |
| `actorSubject` | string or null | Optional source actor subject, limited to 128 characters. |
| `correlationId` | string or null | Optional correlation reference, limited to 128 characters. |
| `causationId` | string or null | Optional causation reference, limited to 128 characters. |
| `supersedesEventId` | UUID or null | Prior event identity for a correction/superseding revision. |
| `currentLifecycleStatus` | string | Required Booking status: `DRAFT`, `PENDING_APPROVAL`, `APPROVED`, `REJECTED`, `CANCELLED`, or `EXPIRED`. |
| `observedAt` | timestamp | Required source observation time. RLC also independently records trusted server-side `receivedAt`. |

The RLC endpoint is `POST /internal/v1/source-evidence/booking-requests`. RLC adds trusted server-side `receivedAt` and a SHA-256 digest of the serialized evidence to each append-only receipt. Receipt outcomes are `ACCEPTED`, `DUPLICATE`, `STALE`, and `CONFLICTED`; rejected outcomes are retained with a reason code. Invalid contract payloads fail validation before insertion. The receipt stores no source payload beyond the listed fields.

This is the current internal JSON v1 shape, not a public Gateway API or broker event schema. The one-category shape and required positive revision are the v1 semantics for this bounded Booking integration. Other source owners require separately validated field mappings and source revision semantics; no compatibility or coverage is implied.

## Validation and persistence behavior

- Unsupported versions, missing required values, malformed codes, invalid authenticated-source match, unconfigured Booking categories, invalid Booking record types/statuses, nonpositive versions/revisions, inconsistent start-basis timestamps, and impossible closure/decision ordering are rejected.
- Exact redelivery of an accepted `(source, event, record, evidence digest)` is recorded as `DUPLICATE`; it does not replace the accepted evidence.
- A lower source revision is recorded as `STALE` and is never selected by the evaluator.
- Reuse of an event ID with different evidence, same-revision divergence, or a revision that does not supersede the latest accepted event is recorded as `CONFLICTED`.
- A conflict at or beyond the latest accepted revision blocks evaluation until a higher valid correction supersedes the current accepted event.
- Per-record PostgreSQL transaction advisory locking serializes concurrent deliveries. Receipt insert and outcome commit atomically; database failure rolls back the transaction. Retrying an uncertain delivery is safe by event identity/digest.
- PostgreSQL rejects receipt `UPDATE`, `DELETE`, and `TRUNCATE`; the runtime database role is granted only `SELECT` and `INSERT`.
- Evaluator output includes the accepted event ID, revision, digest, and server receipt time. Missing evidence, unresolved conflict, stale receipt, or a nonmatching owner-acknowledged revision returns `NOT_ELIGIBLE`. The maximum accepted age defaults to `PT24H` and is configurable with `RLC_SOURCE_EVIDENCE_MAX_AGE`; nonpositive values fail application startup. Eligibility remains distinct from disposal authorization.

## Security and integration boundary

RLC validates the Booking bearer token using RS256, a pinned issuer, current/previous configured key IDs and public keys, exact audience `rra-roomiq-records-lifecycle-source-evidence`, subject `booking-service`, token UUID, issued-at/expiry, maximum two-minute lifetime, and the signed `BOOKING_SOURCE_EVIDENCE_PUBLISH` permission. A request-body filter accepts JSON only and caps the body at 64 KiB. No Gateway route is added.

Booking signs its short-lived service token locally using its configured private key, following the existing project service-token pattern. The dedicated permission is registered in the Identity permission catalog, but Identity does not issue this token or dynamically evaluate the permission during delivery. Production key provisioning, rotation procedures and RRA approval of this identity remain deployment/assurance boundaries.

Booking persists a minimized snapshot and delivery state in its own `booking_source_evidence_outbox` in the same transaction as the booking request change. The existing business-event outbox contract is unchanged. The evidence payload omits title, purpose, participant details, room/department IDs, and other booking contents. RLC receipt persistence is RLC-local and append-only. It is not an Audit Service event and does not replace approved Audit feeds. No direct cross-service database access or physical cross-service foreign key is used.

Booking source revision is the JPA `@Version` value plus one: initial persisted version 0 becomes source revision 1. Each newer snapshot references the previous event ID through `supersedesEventId`. The relay serializes revisions per request; only a matching RLC `ACCEPTED` or `DUPLICATE` acknowledgement marks delivery. Transient errors use capped exponential retry; after ten automatic attempts, or immediately for permanent 4xx/invalid acknowledgement, the row enters reconciliation-required state. An earlier undelivered revision blocks later revisions. Operational monitoring and manual reconciliation are not yet provisioned.

## WP3 retention evaluation integration

Accepted Booking evidence can be supplied to RLC's existing retention evaluator through its source-aware evaluation service. The evaluator selects the latest accepted receipt, requires the requested acknowledged revision to match exactly, rejects stale receipts and unresolved conflicts, and uses the evidence's explicit category and authoritative start. It supplies only the source-declared start basis to policy calculation (and the component timestamps needed for the explicit `LATER_DECISION_CLOSURE` basis); a policy rule with a different start basis fails closed as `RULE_START_MISSING`. Unknown or unmapped categories have no applicable policy and cannot produce eligibility.

The existing RLC policy registry is reused. Evaluation selects one `APPROVED` or `SUPERSEDED` version whose effective interval contains the source authoritative start (`effectiveFrom` inclusive, `effectiveTo` exclusive); draft/withdrawn-only matches, conflicts, and missing policy/rules do not produce eligibility. Fixed-day and calendar-year calculations use UTC instants; exact expiry equality is eligible. Kigali is a display convention, not a calculation timezone.

Flyway V8 persists an append-only evaluation record for each evaluation attempt, including the accepted evidence receipt and digest, source revision and acknowledgement, category/start, evaluation time, policy-version and rule snapshots, and result. Repeated evaluation creates a new history ID but retains the same calculation result when its inputs are unchanged. This record is RLC-local and does not authorize disposal or replace Audit Service evidence.

Booking's runtime mapping remains deliberately unconfigured by default: delivery is disabled, both supported request types require explicit category and supported start-basis values, and RLC independently requires a nonempty allow-list. Synthetic tests use `TEST_BOOKING_CATEGORY`, `RECORD_CREATED_AT`, and `SYNTHETIC_TEST_ONLY` policy references; these values are not operational configuration, legal periods, or RRA approval. No retention policy is activated by deployment configuration.

## WP4 provisional legal-hold control boundary

The separate RLC hold registry is deliberately narrower than the Booking evidence integration: it supports only `booking-service` / `BOOKING_REQUEST`. It does not infer a hold from Booking evidence and does not claim that Booking, archive, or backup copies have enforced the hold.

The RLC API is not Gateway-routed and consists of:

| Method and path | Purpose |
|---|---|
| `POST /api/v1/legal-holds` | Create an idempotent hold record for a supported Booking request. |
| `GET /api/v1/legal-holds/{holdId}` | Read a hold record. |
| `POST /api/v1/legal-holds/{holdId}/release-requests` | Record a release request with a bounded reference. |
| `POST /api/v1/legal-holds/{holdId}/release-verification` | Verify a pending release with a separate bounded reference. |

Each operation requires a validated bearer token and a current Identity authorization decision: active unscoped `SUPER_ADMIN` plus `RECORDS_HOLD_MANAGE`. Identity derives the actor ID from the token subject and current database permission state; request data cannot supply an actor or grant. Identity Flyway V9 catalogs the permission without assigning it automatically. RLC applies request-body limits. The Identity authorization endpoint is internal and fail-closed; the hold API is not a public Gateway route.

The registry states are `PENDING_BLOCK`, `CONFLICTED`, `RELEASE_PENDING`, `RELEASE_ENFORCEMENT_PENDING`, and `RELEASED`. A new hold starts `PENDING_BLOCK`; an additional unresolved hold on the same record is `CONFLICTED`. All states except `RELEASED` block retention eligibility. A release request and independent verification do not unblock the record: only a matching Booking acknowledgement of persisted `RELEASED` state does so. Other unresolved holds continue blocking. Duplicate requests with the same actor, operation, idempotency key and request are replay-safe; changed request reuse conflicts.

Flyway V10 adds the RLC-local hold registry, adds the three hold event types to the existing control-event vocabulary, enforces scoped state transitions and matching lifecycle-event evidence in PostgreSQL, and denies hold deletion/truncation to runtime operations. Hold state and lifecycle evidence are written transactionally. Evaluation, source-evidence ingestion, and hold mutation serialize on the same per-record advisory lock; evaluation history records hold IDs, states, versions, and release-verification facts. `PENDING_BLOCK` is a conservative RLC-side block, not confirmation of source-owner enforcement or a legally authoritative instruction.

This implementation does not resolve HOLD-01 through HOLD-05. RRA/legal authority, approved initiation/release rules, operational monitoring/reconciliation, archive/backup coverage, and production permission assignment remain outstanding. It does not activate retention, authorize disposal, or implement archive transfer or disposal execution.

## WP5 Booking hold instructions and acknowledgements

RLC writes an instruction to its own `lifecycle_hold_instruction_outbox` in the same transaction as creation or independent release verification. It sends `POST /internal/v1/legal-holds/instructions` to Booking over HTTPS; the endpoint is not Gateway-routed. Instruction v1 contains `contractVersion`, stable `instructionId`, `holdId`, per-hold `instructionRevision` (1 for APPLY, 2 for RELEASE), `operation`, owner `sourceService=booking-service`, `recordType=BOOKING_REQUEST`, canonical lowercase Booking request UUID `recordId`, SHA-256 of the original instruction reference, `issuedAt`, and optional `correlationId`. The authenticated sender is **not** inferred from `sourceService`: Booking requires the separately authenticated `records-lifecycle-service` principal.

The sender signs a short-lived RS256 token with the provisioned RLC key; Booking validates its pinned issuer and current/previous key, signature, exact audience `rra-roomiq-booking-legal-hold`, subject `records-lifecycle-service`, token UUID, issued-at, expiry and signed `BOOKING_SOURCE_HOLD_ENFORCE` permission. Identity Flyway V10 catalogs that permission but does not issue the token, make a runtime permission decision, or automatically assign it. This follows the existing locally signed workload-token pattern; deployment must independently protect and provision the key and Booking HTTPS server identity. No cross-service database access or public route is added.

Booking locks the identified request, validates revision and instruction digest, then commits a local hold and append-only receipt in one transaction. Booking returns `instructionId`, `holdId`, revision, `outcome`, actual enforcement status, and Booking request version. Outcomes are `ACCEPTED`, `DUPLICATE`, `STALE`, `CONFLICTED`, `REJECTED`, and `RECONCILIATION_REQUIRED`; a failed transaction cannot acknowledge acceptance. Active local holds reject protected request and associated reservation lifecycle changes and extension operations. Deletion guards protect the request and its reservation. Guards and instruction delivery serialize on the Booking request row. This is limited to the identified Booking request and its implemented operation guards, not archive copies, backups, or other source owners.

RLC leases due rows and delivers earlier revisions first. Only a matching `ACCEPTED` or `DUPLICATE` response with the expected persisted state and a valid Booking request version is acknowledged; mismatches and permanent HTTP errors require reconciliation. Transient errors use exponential backoff; the default is 12 attempts before reconciliation. Expired leases can be reclaimed; late responses cannot complete a newer claim. A release remains blocking in RLC until Booking confirms its local release. The outbox retains correlation and hashed references rather than case narratives.

This is asynchronous and at-least-once: **before Booking durably accepts APPLY, RLC's pending block is not proof of Booking enforcement**, and Booking cannot enforce an instruction it has not received. Existing holds created before the outbox existed require operator reconciliation; no automatic backfill is claimed. Monitoring, a supported operator redrive/reconciliation workflow, production key/TLS provisioning and a live deployed cross-service test remain outstanding. Local contract tests now use the shared synthetic fixture `api-contracts/fixtures/booking-rlc-v1.json`, serialize both services' DTOs, and exercise each real signer against its counterpart verifier, including invalid claims; these tests do not establish deployed connectivity or operational trust. The Compose profile exposes settings but intentionally leaves keys, allow-lists and the HTTPS Booking endpoint empty/disabled until provisioned. `RLC_BOOKING_HOLD_SERVICE_URL` is mapped to the RLC process's `BOOKING_SERVICE_URL` without replacing the general local service URL. The direct Booking Compose listener is HTTP and must not be used as the hold-instruction target; configure a trusted HTTPS ingress/proxy separately. Do not equate transport success, a configured signer, or the project baseline with RRA approval.

## Verification

`SourceOwnerEvidenceValidatorTests` cover required fields, unsupported versions, malformed/unconfigured categories, source mismatch, lifecycle status and start-basis validation. `BookingSourceEvidenceJwtDecoderTests` cover a valid RS256 token, signature, expiry, issuer, exact audience, subject, permission, lifetime, and previous-key rotation. The test-only `BookingRlcInteroperabilityTests` module uses the real Booking signer/RLC decoder and RLC signer/Booking interceptor with a synthetic keypair, and rejects wrong issuer/audience, expired token, unknown key ID, missing permission, wrong subject, and overlong lifetime in both directions. Booking's `BookingRlcContractFixtureTests` and RLC's `SourceOwnerEvidenceContractFixtureTests` independently serialize/deserialize their DTOs against the shared fixture. `SourceOwnerEvidencePostgresTests` cover append-only privileges, valid endpoint receipt persistence, duplicate delivery, corrections, stale/out-of-order updates, conflicts and recovery by newer correction, source isolation, missing/unacknowledged/stale evidence, endpoint authorization/payload constraints, evaluator provenance, and transaction rollback. WP4 coverage includes Identity authorization, unauthenticated/denied requests, overlapping hold states, release separation, persisted evaluator blocking/provenance, runtime mutation rejection, and transactional rollback of hold state and lifecycle event. Booking's PostgreSQL tests cover mapped/unmapped categories, source revisions, supersession, and transactional outbox rollback; relay tests cover durable acknowledgement, transient retry, permanent failure, retry exhaustion, and disabled relay. The focused Booking/RLC/contract selection passed 40 tests; the full Identity suite passed 80 tests (common-web 6); the final backend reactor passed 626 tests across 12 modules, all with zero failures, errors, or skips (exit code 0). These tests use PostgreSQL/Testcontainers where persistence applies but do not prove production key provisioning, live deployed interoperability, external reconciliation, or RRA approval.
