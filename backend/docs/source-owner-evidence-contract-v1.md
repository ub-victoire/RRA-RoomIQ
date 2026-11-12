# RLC Source-Owner Evidence Contract v1 — Booking Integration

## Status and scope

This note documents the implemented technical contract and Booking-to-RLC integration for S8-06 Work Package 2. The integration follows the provisional project baseline and is **pending RRA review — not yet validated**. It is not formal RRA approval, legal validation, records-management approval, production identity approval, or policy activation.

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

## WP4 legal-hold evaluation boundary

The RLC service now owns a persistent legal-hold registry in Flyway V9. The service-level API is `/api/v1/legal-holds/**`; there is no Gateway route. Identity re-evaluates the bearer-token subject against current account and permission state for each API call, requiring an active global Super Admin with the dedicated `LEGAL_HOLD_MANAGE` permission. Identity V8 catalogs that permission but does not grant it. RLC takes the actor ID only from the trusted Identity response.

The registry supports exact Booking/other-source `RECORD` scope and explicit `CATEGORY` scope; unsupported organization/building scopes are rejected. The lifecycle is `DRAFT → PENDING_APPROVAL → ACTIVE` or `REJECTED`, then `ACTIVE → RELEASE_PENDING → RELEASED`; active holds can be amended with a new version. Versioned transitions use `If-Match`, idempotency keys, transaction-scoped PostgreSQL advisory locks and row locks. Initiator and approver/release separation is enforced, and release requires a distinct requester and approver. Successful transitions append before/after state and scope snapshots to the shared RLC `lifecycle_control_event` log, whose update/delete/truncate protections also apply to hold history.

Source-aware evaluations query only persisted RLC hold state, matching exact record and/or configured category scopes. `PENDING_APPROVAL`, `ACTIVE`, and `RELEASE_PENDING` block; overlapping holds are returned separately, so releasing one leaves others in effect. Evaluation acquires matching advisory scope locks before lookup and records matched hold IDs, references, versions, scopes, and statuses in append-only evaluation provenance. Hold lookup failure returns a fail-closed evaluation. A reference supplied during release is recorded but is not a verified owner acknowledgement.

This is not source-owner enforcement. Booking and other owners do not receive hold commands or send acknowledgements; copies, archive, and backups are not covered. The workflow and scopes remain a provisional technical implementation pending RRA review. No hold grants disposal authority, activates a policy, transfers an archive, or deletes data.

## Verification

`SourceOwnerEvidenceValidatorTests` cover required fields, unsupported versions, malformed/unconfigured categories, source mismatch, lifecycle status and start-basis validation. `BookingSourceEvidenceJwtDecoderTests` cover a valid RS256 token, signature, expiry, issuer, exact audience, subject, permission, lifetime, and previous-key rotation. `SourceOwnerEvidencePostgresTests` cover append-only privileges, valid endpoint receipt persistence, duplicate delivery, corrections, stale/out-of-order updates, conflicts and recovery by newer correction, source isolation, missing/unacknowledged/stale evidence, endpoint authorization/payload constraints, evaluator provenance, and transaction rollback. Booking's PostgreSQL tests cover mapped/unmapped categories, source revisions, supersession, and transactional outbox rollback; relay tests cover durable acknowledgement, transient retry, permanent failure, retry exhaustion, and disabled relay. These tests use PostgreSQL/Testcontainers for persistence behavior but do not prove production key provisioning, live deployed interoperability, external reconciliation, or RRA approval.
