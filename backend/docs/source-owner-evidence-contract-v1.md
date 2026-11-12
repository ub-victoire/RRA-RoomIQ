# RLC Source-Owner Evidence Contract v1 — Implementation Note

## Status and scope

This note documents the RLC-side internal contract foundation implemented for S8-06 Work Package 1. It is **not an approved wire contract** and does not authorize an API, event subscription, source integration, category mapping, service identity, or policy activation. No source service currently sends this contract to RLC.

The candidate source is Identity for Identity-owned authentication records. Identity already emits versioned `IdentityAuditEvent` v1 messages to Audit, including authentication event types, event identifiers, timestamps, actor/resource references, outcomes, and correlation IDs. That existing contract is not an RLC lifecycle-evidence contract; it has no agreed RLC source revision, category mapping, delivery authorization, or RLC consumer. No source is connected by this work.

## Internal v1 evidence fields

The Java record is `SourceOwnerEvidence`. Its implemented fields are:

| Field | Type | Requirement and meaning |
|---|---|---|
| `contractVersion` | integer | Must equal `1`; unknown versions are rejected. |
| `sourceService` | string | Lowercase service identifier; must equal the `authenticatedSourceService` argument supplied by the future trusted adapter. The argument is not authenticated by this class. |
| `recordType` | string | Uppercase stable type code for the owner record. |
| `recordId` | string | Opaque stable source record identifier, 1–128 characters. |
| `eventId` | UUID | Stable event identity used for duplicate and event-reuse detection. |
| `eventType` | string | Uppercase owner event code. |
| `eventVersion` | positive integer | Source event schema/version. |
| `sourceRevision` | positive integer | Monotonic per-record revision used to order source changes. The owner must define and guarantee this property before integration. |
| `categoryCode` | string | Uppercase policy category code. Syntax is validated; an authoritative taxonomy and source mapping are not yet available. A category without an applicable approved policy/rule cannot become eligible. |
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

RLC adds server-side `receivedAt` and a SHA-256 digest of the serialized evidence to each append-only receipt. Receipt outcomes are `ACCEPTED`, `DUPLICATE`, `STALE`, and `CONFLICTED`; rejected outcomes are retained with a reason code. Invalid contract payloads fail validation before insertion. The receipt stores no source payload beyond the listed fields.

This internal v1 shape is deliberately not represented as an API or broker schema. In particular, its single `categoryCode` and required revision have not been reconciled with the draft S8-06E-03 envelope's `categoryCodes` and owner-specific event/revision rules. Source-owner confirmation and an approved mapping are required before wiring any producer.

## Validation and persistence behavior

- Unsupported versions, missing required values, malformed codes, invalid source-identity match, nonpositive versions/revisions, inconsistent start-basis timestamps, and impossible closure/decision ordering are rejected.
- Exact redelivery of an accepted `(source, event, record, evidence digest)` is recorded as `DUPLICATE`; it does not replace the accepted evidence.
- A lower source revision is recorded as `STALE` and is never selected by the evaluator.
- Reuse of an event ID with different evidence, same-revision divergence, or a revision that does not supersede the latest accepted event is recorded as `CONFLICTED`.
- A conflict at or beyond the latest accepted revision blocks evaluation until a higher valid correction supersedes the current accepted event.
- Per-record PostgreSQL transaction advisory locking serializes concurrent deliveries. Receipt insert and outcome commit atomically; database failure rolls back the transaction. Retrying an uncertain delivery is safe by event identity/digest.
- PostgreSQL rejects receipt `UPDATE`, `DELETE`, and `TRUNCATE`; the runtime database role is granted only `SELECT` and `INSERT`.
- Evaluator output includes the accepted event ID, revision, digest, and server receipt time. Missing evidence, unresolved conflict, or a nonmatching owner-acknowledged revision returns `NOT_ELIGIBLE`. Eligibility remains distinct from disposal authorization.

## Security and integration boundary

No HTTP endpoint, message listener, workload-token verifier, source authorization check, payload-size filter, or source-outage retry policy is implemented. `authenticatedSourceService` is a method argument and must never be populated from an untrusted request body or caller claim. Do not expose this service as a controller or listener until the approved workload identity and source-specific authorization are implemented and tested.

No direct cross-service database access or cross-service foreign key is used. The RLC receipt is its own persistence boundary. It is not an Audit Service event; no Audit delivery was added because there is no approved RLC audit event/authorization contract for this action. Do not log evidence contents or sensitive identifiers when adding a future adapter.

The store detects out-of-order revisions but cannot establish whether the source has newer evidence that was never delivered, whether the source is available, or whether the current source owner has acknowledged the revision in real time. There is no source snapshot freshness SLA in the repository, so none is invented here. A future adapter must bind an authenticated owner acknowledgement to the exact current revision and fail closed on unavailable, stale, malformed, unauthorized, or conflicting source evidence.

## Verification

`SourceOwnerEvidenceValidatorTests` cover required fields, unsupported versions, malformed category syntax, source mismatch, and the later-of-decision/closure timestamp requirements. `SourceOwnerEvidencePostgresTests` cover append-only privileges, valid receipt persistence, duplicate delivery, corrections, stale/out-of-order updates, conflicts and recovery by newer correction, source isolation, missing/unacknowledged evidence, evaluator evidence/policy provenance, and transaction rollback. `PolicyRegistryPostgresTests` verify the V6 schema and runtime privileges. These tests use PostgreSQL/Testcontainers and do not prove source-service interoperability, workload authentication, or a live source-to-RLC integration.
