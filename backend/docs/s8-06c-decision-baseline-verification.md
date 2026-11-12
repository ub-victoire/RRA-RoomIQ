# S8-06C Provisional Decision Baseline Verification and Implementation Backlog

**Verification date:** 2026-11-12
**Scope:** Read-only audit of the S8-06B project baseline and Stage 8 implementation readiness
**Decision authority:** Project CTO / Project Lead
**Decision status:** Project-level decisions remain `PROVISIONALLY ADOPTED` and `PENDING RRA REVIEW — NOT YET VALIDATED`
**Stage status:** S8-06 and S8-07 are not complete; no Stage 8 acceptance is claimed

## 1. Executive summary

The authoritative S8-06A register contains exactly 36 substantive decision rows, one for each required original ID. The S8-06B document also has one table row for each of those 36 IDs, and does not add ARC-08 or DSP-06 to that inventory. Those checks pass.

The provisional baseline removes the need to wait for RRA to choose safe project-level engineering defaults: source systems remain authoritative for their own facts; evaluation is fail-closed and non-destructive; only approved policy versions can govern eligibility; missing or conflicting evidence blocks eligibility; eligibility is not disposal authorization; and no archive transfer or disposal execution is authorized by the provisional package. These decisions permit implementation of safe foundations and non-destructive validation work.

The baseline is not yet a complete per-decision implementation directive. The S8-06B inventory rows identify the decisions and status, but use grouped or generic outcomes rather than recording a decision-specific rationale, implementation consequence, and acceptance evidence for every ID. The original register has detailed recommendations and acceptance evidence, but those records still show `OPEN`; its technical protocols remain explicitly proposals, not adopted S8-06B requirements. The documents therefore need a project-level crosswalk before implementation teams can treat every original ID as fully resolved for engineering.

The largest remaining gaps are actual implementation and integration: no source-owner lifecycle evidence ingestion, cross-category retention evaluation, persisted hold register or owner enforcement, archive workflow, or disposal workflow exists. The detailed H.4/H.5 proposals are a coherent technical starting point for holds, archive, and disposal, but are not represented as adopted decisions in S8-06B and are not implemented. No direct cross-service database access is proposed or found.

Formal RRA policy validation and approval, source-owner and operational acknowledgements, production DBA/broker/secret provisioning, and current final assurance evidence remain distinct gates. The 2026-11-05 reports cited by the register are historical and predate the 2026-11-08 RLC changes. There is no live multi-service lifecycle E2E evidence. S8-06 remains blocked for acceptance, and S8-07 remains blocked.

## 2. Scope and evidence examined

This was a read-only inspection. The authoritative sources examined were:

- [Backend Documentation](../Documentation.md), including the approved Stage 8 checklist, S8-06 and S8-07 acceptance criteria, current status and changelog.
- [S8-06 implementation-readiness register](s8-06-implementation-readiness-register.md), including the 36-row Section I inventory, decision worksheets, test evidence, and H.1–H.11 technical proposals.
- [S8-06B provisional decisions](s8-06b-provisional-decisions.md).
- [RLC README](../records-lifecycle-service/README.md), RLC Java classes, Flyway migrations, and unit/PostgreSQL test sources.
- [Audit README](../audit-service/README.md), Audit entity/processors, migrations, tests, and the [Audit action-owner matrix](audit-action-owner-matrix.md).
- Available Surefire reports and the repository's recorded runtime validation evidence.

No application tests were rerun. Existing test reports were treated as historical repository evidence, not as evidence of a current rerun.

## 3. S8-06A inventory verification

The Section I inventory in the authoritative register contains **36 rows and 36 unique required IDs**. The S8-06B inventory table contains **36 individual ID rows**. No duplicate or missing ID was found in either inventory.

| Group | IDs | Count | Inventory result |
|---|---|---:|---|
| Policy | `S8-06A-POL-01`–`POL-04` | 4 | Present once each |
| Time and calculation | `S8-06A-TIM-01`–`TIM-03` | 3 | Present once each |
| Ownership | `S8-06A-OWN-01`–`OWN-02` | 2 | Present once each |
| AI records | `S8-06A-AI-01` | 1 | Present once |
| Archive | `S8-06A-ARC-01`–`ARC-07` | 7 | Present once each |
| Legal hold | `S8-06A-HOLD-01`–`HOLD-05` | 5 | Present once each |
| Disposal | `S8-06A-DSP-01`–`DSP-05` | 5 | Present once each |
| Audit | `S8-06A-AUD-01` | 1 | Present once |
| Database | `S8-06A-DB-01` | 1 | Present once |
| Technical | `S8-06A-TECH-01`–`TECH-03` | 3 | Present once each |
| Operations | `S8-06A-OPS-01`–`OPS-02` | 2 | Present once each |
| Security | `S8-06A-SEC-01` | 1 | Present once |
| Testing | `S8-06A-TEST-01` | 1 | Present once |
| **Total** |  | **36** | **All present once** |

### Per-decision completeness

The Section I register preserves the original 36 decision questions, current `OPEN` status, rationale, evidence, owner/stakeholder, recommendation/alternatives, dependencies, and closure evidence. The linked approval forms are still pending. S8-06B assigns the common provisional owner and review status and records one row for every ID.

However, Section I continues to say all 36 are `OPEN`, while S8-06B says they are `PROVISIONALLY ADOPTED`. This can be reconciled as “project implementation baseline adopted; formal RRA validation/approval still open,” but the documents do not use that two-axis status consistently. Also, S8-06B does not state a sufficiently specific outcome for each original decision: many rows group several IDs under a broad design direction. In particular, it does not individually bind each ID to an implementation consequence, an acceptance test/evidence item, and an accountable project technical owner. Those details exist partly in the register's original recommendations and H-section proposals, but the latter are marked proposals and the original rows remain open.

**Result:** inventory preservation passes; decision-level implementation traceability is **partial**. This is a project documentation/crosswalk gap, not a reason to return technical design choices to RRA. The Project CTO / Project Lead and technical owners can resolve the project-level mapping. Formal Legal/Records Management validation remains a separate external approval boundary.

## 4. Baseline consistency findings

### 4.1 Provisional decision boundaries

The baseline consistently disclaims RRA approval, legal validation, and operational readiness. It does not invent a legal citation or add a new retention duration. The proposed durations in the existing readiness register are explicitly described as supplied, proposed, and unvalidated.

The following project-level engineering positions are clear enough to proceed with safe foundations:

- source services own authoritative facts and local persistence;
- RLC may coordinate lifecycle control but must not access another service's database;
- missing, unknown, conflicting, or unavailable evidence fails closed;
- eligibility evaluation is non-destructive;
- only an approved policy may make a record eligible;
- a hold blocks disposal, and eligibility never authorizes disposal;
- archive/disposal execution is not enabled by S8-06B.

The ambiguity is not whether to wait for RRA on technical choices. It is that the provisional decisions document does not select and bind enough technical detail to each decision to direct implementation consistently.

### 4.2 Original 36 decisions and their substantive outcomes

S8-06B lists every ID separately, but the outcomes are often broad statements (for example “configurable,” “explicit,” or “fail-closed”) rather than a concrete choice with rationale and testable acceptance criteria for that ID. The source register's recommendations do not automatically become adopted decisions because the register identifies H.1–H.11 as proposals and the decision rows remain `OPEN`.

The highest-impact items needing a project-level crosswalk are:

- **POL/TIM:** Exact calendar-year vs fixed-day behavior, UTC normalization, expiry equality, effective-version selection, retroactive recalculation, and interpretation of closure-based starts are described in the register as proposals, not explicitly adopted in S8-06B.
- **OWN/TECH:** The source-of-truth boundary is adopted, but authenticated source identity, per-owner event/revision identity, receipt-time provenance, ordering, idempotency/conflict policy, and acknowledgements are not adopted as a complete contract.
- **AI-01:** Explicit, source-owned classification is stated, but the later-of-decision/closure start semantics, qualifying closure event, and source owner remain conditional and unresolved. No AI record source was found.
- **ARC-01–07:** Configuration and “verification hooks” are broad; destination selection, manifest shape, signing/integrity approach, verification independence, retry/quarantine, and recovery acceptance criteria are in H.5 but not explicitly adopted by S8-06B.
- **HOLD-01–05:** The S8-06B document adopts only explicit status, block-on-active, authorized recorded release, and manual escalation for overlaps. The detailed scope, acknowledgement, fence, queued-work cancellation, single-use execution claim, release, and in-flight race protocol in H.4 remains a proposal.
- **DSP-01–05:** The separation between eligibility and authorization is clear. Snapshot binding, named distinct approver/executor/verifier, pre-execution recheck, partial outcome model, and certificate/reconciliation requirements are detailed in H.5 but not adopted as decision-specific baseline requirements.
- **AUD/DB/OPS/SEC/TEST:** “Append-only,” role separation, fail-closed security and testing are principles, not complete retention/privilege/runbook/monitoring/acceptance specifications. Current implementation limitations remain material.

### 4.3 ARC-08 and DSP-06

The authoritative register contains ARC-08 criteria and DSP-06 criteria in its technical/control material, but the original Section I inventory intentionally contains only the 36 original rows and has no `S8-06A-ARC-08` or `S8-06A-DSP-06` decision row. S8-06B correctly preserves that distinction and labels both as traceability gaps. They must not be silently added to the original 36.

The gap is not resolved by the S8-06B baseline: an accountable project owner must determine whether those criteria are covered by an existing decision/acceptance row or require an explicitly authorized supplementary record. No additional decision ID is asserted by this report.

### 4.4 HOLD-01 through HOLD-05: lifecycle and concurrency

The register's H.4 proposal forms a coherent lifecycle model:

1. RLC records a hold as pending and establishes a monotonic fence.
2. Each source owner resolves scope locally, applies local hold membership, and acknowledges only after queued/prepared work can no longer cross into execution.
3. Independent holds remain separate; release of one does not release another.
4. RLC must not report active until owners acknowledge; unknown, stale, unavailable, rejected, or partial state blocks dependent disposal.
5. Disposal admission is serialized against hold registration using a single-use batch/fence claim; already irreversible work cannot be undone and must be surfaced/escalated.
6. Release advances the fence; prior disposal batches do not become valid again.

That is a coherent technical proposal and preserves service ownership. It is **not** a persisted workflow or adopted S8-06B protocol. HOLD-01 authority and written evidence, HOLD-02 scope/versioning, HOLD-03 acknowledgement contract, HOLD-04 release/conflict rules, and HOLD-05 irreversible-race semantics still have `OPEN` records. The critical unresolved project boundary is how to stop before irreversible execution when a hold and execution race. This can be implemented conservatively as “no irreversible action while hold state is uncertain”; legal/effective-time treatment of already in-flight work remains a policy/operational boundary, not a technical design choice to send back to RRA.

### 4.5 Retention calculation, policy versioning, and evidence provenance

**Implemented:** `RetentionEligibilityCalculator` is read-only and fail-closed for missing owner/category/start/evaluation time, missing or conflicting effective policy, unapproved policy state, missing rule/schedule inputs, unavailable hold state, active/unknown hold statuses, and unexpired retention. It computes the maximum expiry across matching rules for a **single supplied category**, uses UTC calendar-year/day arithmetic, and regards equality at expiry as eligible. `RetentionPolicyEvaluationService` selects effective policy versions and rules from the RLC database.

**Not implemented:** trusted source-owner evidence ingestion, source revision/sequence provenance, cross-category maximum retention, a source-owned classification map, durable calculation input/result history, audit-ready recalculation lineage, and a production path to obtain hold or external schedule state. The evaluator's input is supplied to the service; there is no evidence contract that proves those facts. The returned result contains status/reason, expiry, policy version ID, and applicable rule codes, not a full immutable provenance snapshot.

The register H.3 proposal distinguishes occurrence time from transport/outbox/ingestion time, recommends an effective policy selected at record start, prohibits silent shortening, and requires explicit retroactive recalculation. S8-06B adopts the source-time and fail-closed principles but does not expressly adopt those detailed version/recalculation semantics. The `RetentionEvaluationEvidence` also has a top-level `authoritativeStartAt` used for policy selection plus per-basis timestamps used for expiry; how these must agree is not defined by the present source contract. Implementations need a single, documented provenance rule and mismatch tests.

### 4.6 Archive and preservation

The H.5 proposal is technically coherent and service-owned: deterministic manifest and per-object digests, source identity/signature, TLS transfer to an approved destination, independent verification, source/destination reconciliation, idempotent retry, quarantine on mismatch, and isolated restore checks. It avoids cross-service database access and keeps detailed records with their source owner.

S8-06B adopts only configurable archive requirements and that transfer must not occur without approval. The actual destination, manifest contract, signing/key authority, independent-verifier identity, reconciliation thresholds, restore criteria, and owner acceptance evidence remain unspecified in the provisional baseline. There is no archive provider, transfer, manifest, recovery process, production backup integration, or corresponding test in the repository.

### 4.7 Disposal separation and safeguards

Both documents correctly distinguish non-destructive eligibility from disposal authorization. H.5 proposes a source-owner batch snapshot/digest, separate clearances and senior authorization, distinct authorizer/executor/verifier, immediate recheck of policy/hold/archive evidence, owner-local execution, per-item idempotent outcomes, partial-result reconciliation, and a certificate whose evidence is retained under a separate approved rule.

S8-06B adopts the separation principle but does not explicitly adopt that complete safety protocol. RLC currently exposes policy lifecycle APIs only; no hold/disposal API, permission, role, database state, scheduler, deletion command, certificate, or reconciliation workflow exists. Audit's append-only protections cannot be bypassed as a disposal implementation. There is no basis to claim disposal is authorized or operational.

### 4.8 Source ownership and service boundaries

The S8-06B ownership decision agrees with the existing architecture: source services own their own facts and stores, Audit owns only consumed audit copies, and RLC is a separate control-plane service. The H.1/H.2/H.8 proposals likewise prohibit cross-service database queries and require each owner to resolve its own records, keep local lifecycle state, and acknowledge commands.

This is the right boundary for implementation. However, the proposed S8-06E-03 evidence contract remains unapproved and is not an implemented interface. Identity v1 and Notification template-audit events already reach Audit; neither is an RLC source evidence API. Booking, Room, Scheduling, Organization and other producer paths are not automatically audit/lifecycle contracts. The S8-01 matrix documents material action and fact gaps. Source-owner authentication, source/revision identity, category mapping, allowed fields, receipt time, and error/replay semantics still need project-owned contract decisions and tests. No cross-service database dependency was found in the inspected RLC/Audit paths.

### 4.9 Audit and security requirements

**Verified foundations:** Audit persists supported Identity and Notification template-audit event copies in its own `audit_event` table, deduplicates identical replay and rejects conflicting event-ID reuse. The table is Hibernate `@Immutable`; V2 blocks UPDATE/DELETE/TRUNCATE and grants the runtime role SELECT/INSERT. Audit Search delegates scope/permission authority to Identity. RLC policy endpoints require bearer authentication and the explicit `RETENTION_POLICY_MANAGE` authorization decision.

**Gaps and limitations:**

- `audit_event` contains `occurred_at`, but no dedicated `ingested_at`, record category, retention start/expiry, or retention-policy provenance.
- Producer/action coverage is partial. Existing Audit integration does not make every Booking/Room/Scheduling/administrative action an audit contract.
- Append-only triggers and runtime privileges are not cryptographic tamper evidence and do not constrain a sufficiently privileged database owner/superuser. Production role provisioning remains required.
- `AUDIT_READ` applies to Audit Search; it does not authorize archive, legal hold, retention evaluation, or disposal.
- No hold/disposal-specific authorization, separation-of-duties roles, MFA-backed operational workflow, archive access boundary, or source workload credential configuration is implemented for these workflows.
- No live multi-service lifecycle E2E test exists. Current RLC PostgreSQL tests mock Identity authorization; they are service integration tests, not live Identity-to-RLC or owner-to-RLC E2E.
- Security/privacy coverage for future contracts must explicitly exclude free text, case narrative, payloads, AI prompts/model inputs, credentials, and unrelated personal data; the existing audit metadata field alone does not establish a safe lifecycle contract.

### 4.10 S8-06D, S8-06E-03, and S8-06F

| Item | Verification | Finding / required project action |
|---|---|---|
| `S8-06D` | The current backend roadmap/register does not provide an authoritative definition, owner, output artifact, or acceptance evidence. | **Unresolved checklist-definition gap.** The Project CTO / authoritative checklist owner must recover and define the scope; do not infer a requirement or return an ordinary technical choice to RRA. |
| `S8-06E-03` | The register contains a source-evidence contract proposal with illustrative fields and security/failure semantics, explicitly not an approved interface. S8-06B only calls it unapproved. | The proposal is not a versioned, owner-confirmed contract and has no accepted API/event, field set, authentication, deployment, or conformance evidence. Project technical owners can complete the implementation contract under the provisional ownership boundary; source owners must confirm their facts and operational behavior. |
| `S8-06F` | No authoritative definition, owner, output, or acceptance criteria were found. | **Unresolved checklist-definition gap.** The Project CTO / checklist owner must establish the authoritative item and deliverable before claiming completion. |

Neither undefined item can be marked complete or treated as a reason to invent a feature. They are project/checklist ownership issues, not requests for RRA to select a technical design.

### 4.11 Retention periods and legal references

No new period or legal citation is introduced by S8-06B. The readiness register lists supplied proposed periods (for example, 2 years for access/authentication and 5 years for several categories) and repeatedly labels them proposed and subject to RRA Legal/Records Management validation. Financial/tax and legal/regulatory periods remain governed by applicable legislation and RRA schedules without invented durations. These values must not be configured as operational policy until the separately required validation and formal approval evidence exists.

## 5. Existing implementation and test evidence

| Area | Repository evidence | What it establishes | What it does not establish |
|---|---|---|---|
| RLC policy registry/API | `records-lifecycle-service/src/main/java/rw/rra/roomiq/lifecycle/policy/PolicyLifecycleService.java`, `policy/api/PolicyLifecycleController.java`, `PolicyDefinitionValidator.java`; migrations V1–V5 | Draft/rule CRUD within lifecycle, validation, activation, supersession, withdrawal, approval metadata requirements, transactional lifecycle events and runtime/migrator separation | Approved RRA schedule; source evidence; holds/archive/disposal; production database provisioning |
| RLC evaluation | `retention/RetentionEligibilityCalculator.java`, `RetentionPolicyEvaluationService.java`, `RetentionEvaluationEvidence.java`, `RetentionEvaluationResult.java` | Non-destructive fail-closed evaluation using supplied inputs; policy version effective interval; longest rule within one category; hold statuses supplied to the evaluator | Trusted owner input, cross-category computation, hold register, external schedule integration, durable provenance/recalculation |
| RLC tests | `RetentionEligibilityCalculatorTests.java`, `PolicyRegistryPostgresTests.java`, `LifecycleRuntimeConfigurationTests.java`, `PolicyDefinitionValidatorTests.java` | Unit and PostgreSQL/Testcontainers coverage for the implemented policy/evaluator foundation, role privileges, audited writes, authorization failure paths and calculator cases | Current post-2026-11-08 run; live Identity integration; operational hold/archive/disposal behavior |
| Audit storage and producer paths | `audit-service/src/main/java/.../AuditEvent.java`, processors and listeners; migrations V1–V2; Audit tests | Supported Identity/Notification template events, append-only runtime/trigger protections, deduplication and search coverage | Complete SRS action coverage, retention metadata, archive or disposal |
| Deployment evidence | RLC/Audit READMEs and migration role checks | Explicit external requirements for DBA roles, credentials, broker configuration and deployment | That production provisioning has occurred |

The readiness register states that available Surefire reports are dated **2026-11-05**, aggregate **563 tests across 11 modules**, with zero failures, errors, and skips, and predate the repository's 2026-11-08 RLC implementation commit. The RLC README separately cites 40 tests across four RLC suites on 2026-11-05. Treat these as historical evidence only. No tests were run for this audit.

## 6. Prioritized implementation backlog

The following work packages are proposed implementation units under the project-level provisional baseline. Any contract/schema/migration change still follows the repository's existing change-control and ownership conventions. These packages do not authorize production retention periods, archive destinations, or destructive disposal.

### P0 — Checklist and decision traceability closure

- **Decisions addressed:** all 36; `ARC-08`, `DSP-06`; `S8-06D`, `S8-06E-03`, `S8-06F`.
- **Existing evidence:** 36 original decision rows in register Section I; 36 ID entries in S8-06B; explicit gaps for ARC-08/DSP-06 and undefined D/F; source-evidence proposal in the register.
- **Missing functionality/evidence:** a project-owned crosswalk that links each ID to one precise provisional outcome, rationale, owner, code/package consequence, acceptance evidence, and residual RRA/operational boundary; authoritative definitions and deliverables for D/F; resolution of whether ARC-08/DSP-06 fit existing rows or need separately authorized additions.
- **Likely files/services:** `backend/docs/s8-06-implementation-readiness-register.md`, `backend/docs/s8-06b-provisional-decisions.md`, `backend/Documentation.md`; no service code.
- **DB/API implications:** none in this work package.
- **Security/failure/concurrency:** none; preserve the distinction between project adoption and RRA approval.
- **Tests/acceptance:** deterministic check confirms 36 IDs exactly once in the canonical inventory and one linked decision outcome/acceptance mapping per ID; D/F definitions have owner/output/criteria; ARC/DSP traceability is explicit without altering the original inventory.
- **Dependencies/blockers:** Project CTO / authoritative checklist owner; no RRA technical decision needed. Formal policy approval remains a separate gate.
- **Proceed under provisional baseline:** **Yes**, documentation/traceability only.

### WP-1 — Source-owner evidence contract and ingestion

- **Decisions addressed:** `OWN-01/02`, `TIM-01/03`, `TECH-02/03`, `AI-01`, `AUD-01`, `SEC-01`, `S8-06E-03`.
- **Existing implementation/evidence:** Identity and Notification producer paths to Audit; source-owned event/outbox patterns; RLC's evidence record types are currently evaluator inputs, not an ingestion API. The action-owner matrix identifies missing/partial producers.
- **Missing functionality:** choose a minimized versioned source evidence envelope; source authentication and source-to-principal binding; category/event mapping; stable record/event/revision identity; explicit source occurrence and receipt timestamps; owner acknowledgement; idempotent ingestion and conflict detection; bounded replay/order rules; source-local adapters/outboxes; no cross-service database reads. Start with categories and source events that have actual owners; do not manufacture AI/security/legal/financial sources.
- **Likely services/files:** RLC API/domain/repository/messaging and migrations; owner-specific modules/outboxes only after source mapping; Audit only for audit-owned copies. Likely RLC areas include `records-lifecycle-service/src/main/java/rw/rra/roomiq/lifecycle/retention/` and `policy/`; corresponding owner service event adapters and tests; no API Gateway route.
- **Database/API contract implications:** likely RLC-owned evidence/receipt tables and a private owner-to-RLC API or messaging contract. Identity's Audit v1 and existing business-event contracts must not be repurposed as RLC contracts without explicit compatibility review. Store references/digests and minimal facts; keep source records and detailed evidence local.
- **Security requirements:** workload identity, TLS, least privilege, source identity bound to credentials, explicit allowed fields, no forwarded user token as workload identity, secret manager credentials, no PII/payload logs.
- **Failure/concurrency:** duplicate replay, reused event ID with changed body, stale revision, out-of-order events, owner outage/timeout/401/403/5xx, malformed/unknown fields, clock skew and source correction. Unknown/stale/conflicting state must not produce eligibility.
- **Automated tests/acceptance:** codec/contract tests; source transaction/outbox atomicity; authenticated source binding; PostgreSQL idempotency/conflict tests; retry/backoff and poison behavior; missing/late/duplicate evidence tests; prove no cross-database access and no sensitive payload logging.
- **Dependencies/blockers:** P0 mapping; service-owner commitment and infrastructure for private transport/broker. Category-specific RRA classification/period validation is still required before operational evaluation, but not before implementing the transport/fail-closed foundation.
- **Proceed under provisional baseline:** **Yes** for contract design, stubs, and non-destructive ingestion foundation. Do not label proposed fields as RRA-approved or enable unvalidated categories operationally.

### WP-2 — Retention policy evaluation, provenance, and recalculation

- **Decisions addressed:** `POL-01..04`, `TIM-01..03`, `OWN-01`, `AI-01`, `DSP-01`, `TECH-01`, `TEST-01`.
- **Existing implementation/evidence:** policy registry, approval metadata constraints, effective interval selection, UTC calendar-year/day calculation, expiry equality, and max expiry among rules for one category; calculator unit tests and PostgreSQL tests.
- **Missing functionality:** cross-category/category-rule union and maximum expiry; authoritative source evidence input; agreement between `authoritativeStartAt` used for version selection and per-basis rule starts; durable, reproducible calculation snapshot (source revision/digest, category set, policy/rule versions, schedule result, hold state version, evaluation time, outcome); explicit recalculation/retroactivity and non-shortening behavior; external schedule adapter with versioned result; independent expected-result fixtures.
- **Likely files/services:** RLC `retention/RetentionEligibilityCalculator.java`, `RetentionPolicyEvaluationService.java`, input/result/evidence records, policy repositories and migrations; RLC tests.
- **Database/API contract implications:** extend RLC-owned evaluation evidence/result persistence and internal evaluation interface as needed; do not change Audit's frozen API/event contract for convenience. Schema changes require migration and owner review.
- **Security requirements:** only approved/effective policy is evaluable; explicit authorization for policy administration and evidence; segregate policy registry approval metadata from project baseline; avoid logging record contents.
- **Failure/concurrency:** overlapping effective policies, missing category/start, inconsistent start bases, stale or corrected source revision, missing schedule response, policy supersession during evaluation, retry/recalculation duplication, and uncertain holds all fail closed. Serialize policy version transitions and ensure evaluations bind to one consistent snapshot.
- **Automated tests/acceptance:** before/equal/after expiry, UTC and calendar-vs-day arithmetic, leap day, effective interval edges, multiple categories selecting maximum, status and version transitions, source revision stability, approved schedule result mismatch, recalculation without silent shortening, missing/conflicting inputs, hold-state unavailability.
- **Dependencies/blockers:** WP-1 evidence envelope and P0 traceability. Formal RRA policy validation is required before using real periods or treating results as operationally eligible.
- **Proceed under provisional baseline:** **Yes** for non-destructive computation and deterministic test fixtures. **No** for operational eligibility decisions based on unvalidated policy content.

### WP-3 — Persisted legal-hold lifecycle and source-owner enforcement

- **Decisions addressed:** `HOLD-01..05`, `OWN-02`, `TECH-01/03`, `SEC-01`, `DSP-02/03`.
- **Existing implementation/evidence:** calculator accepts supplied hold statuses and blocks on active, pending, unknown, conflicted, unavailable, or unacknowledged values; lifecycle event table already allows HOLD aggregate type. There is no hold entity, workflow, endpoint, permission, adapter, or enforcement.
- **Missing functionality:** hold instruction/reference and authorization workflow; bounded/versioned scope; per-owner membership and acknowledgements; independent overlapping holds; pending/active/release-pending/released/conflicted states; owner-local enforcement; monotonic fence and cancellation of not-yet-started work; release of one hold without clearing another; reconciliation and evidence history.
- **Likely services/files:** RLC lifecycle service, API/security/repository, lifecycle migrations, event writer and Testcontainers tests; each records-owning service needs a local adapter and transaction/locking behavior. No centralized query into owner databases.
- **Database/API contract implications:** RLC hold registry and owner-ack/status records plus owner-local hold membership/command evidence; authenticated internal commands and acknowledgements; commands need stable IDs, scope version, and fence. New contract and migrations must preserve service ownership.
- **Security requirements:** authenticated initiating/releasing actor; distinct policy roles for request/approval/release where applicable; least privilege; written evidence references/digests; prevent caller-selected actor/scope; sensitive case narratives excluded; record audit trail append-only.
- **Failure/concurrency:** owner timeout/unavailability, partial application, stale/duplicate/reordered command, overlapping holds, release with other active holds, hold arriving against prepared/queued/claimed work, and irreversible in-flight action. No `ACTIVE` acknowledgement or destructive execution while state is uncertain; already irreversible work is escalated and not falsely reported as reversible.
- **Automated tests/acceptance:** PostgreSQL lifecycle transition/append-only tests; owner command/ack idempotency; overlapping holds; stale fence rejection; concurrent registration vs prepared/queued execution; release-one-only; unavailability and partial acknowledgement; prove no cross-database reads and no false ACTIVE state.
- **Dependencies/blockers:** WP-1 source identity and adapters; WP-2 stable eligibility; project authorization/SoD model. Before real hold instructions are operational, RRA must validate authority, scope and release policy; technical implementation of fail-closed controls can proceed now.
- **Proceed under provisional baseline:** **Yes** for persistence, APIs, owner-local enforcement, and conservative race prevention. No production hold workflow should claim RRA authority until instruction/approval requirements are validated.

### WP-4 — Archive transfer, integrity, recovery, and reconciliation

- **Decisions addressed:** `ARC-01..07`, `OWN-01/02`, `AUD-01`, `DB-01`, `OPS-01/02`, `SEC-01`, and ARC-08 traceability gap.
- **Existing implementation/evidence:** no archive provider, transfer manifest, archive metadata, cryptographic verification, backup lifecycle, recovery, reconciliation, or archive test exists. Audit database append-only controls apply only to primary `audit_event`.
- **Missing functionality:** project-selected archive service boundary; versioned deterministic manifest; per-record and manifest digests/signature; secure transfer; independent verifier; source/destination reconciliation; retry/idempotency and mismatch quarantine; recovery/restore test; source-local evidence and RLC reference/digest only.
- **Likely services/files:** a new archive adapter/service or selected existing infrastructure integration; source-owner modules for export/manifest; RLC for status/reference; infrastructure/deployment configuration and docs; Audit-specific adapter must not weaken Audit V2 protections.
- **Database/API contract implications:** likely archive manifest/transfer metadata owned by the source or a dedicated archive control component; immutable object references, digest, status, counts and verification evidence. Destination-specific contracts/configuration require project selection. Do not add retention fields to Audit's approved ERD without separate change approval.
- **Security requirements:** encryption in transit/at rest, key management and rotation, narrowly scoped archive access, service identity, independent verifier, signed manifests, privacy minimization, secret-manager delivery and restricted operational logs.
- **Failure/concurrency:** partial upload, retry/duplicate transfer, tampered/missing object, stale source snapshot, mismatched digest/count/bytes, key rotation, unavailable verifier, restore failure, holds changing during transfer, and retry after quarantine. Any mismatch blocks downstream disposal.
- **Automated tests/acceptance:** deterministic manifest; signature/digest corruption; duplicate and interrupted transfer; count/byte/source reconciliation; hold exclusion; access-denial tests; restore into isolated environment and independently validate object count, digest, metadata, and permissions. Operational evidence must identify actual destination and owner.
- **Dependencies/blockers:** WP-1 source evidence; WP-2 policy evaluation; WP-3 hold status; project-selected provider and operational key/DB provisioning. RRA validation of destination/data residency and preservation rules is required before production transfer, but technical adapters and local test harnesses can proceed.
- **Proceed under provisional baseline:** **Yes** for provider-neutral interfaces and non-production test harnesses. **No** for real record transfer or claiming approved archive custody until destination and operational governance are validated.

### WP-5 — Disposal clearance, execution, certificates, and independent reconciliation

- **Decisions addressed:** `DSP-01..05`, `HOLD-01..05`, `AUD-01`, `SEC-01`, `TEST-01`, and DSP-06 traceability gap.
- **Existing implementation/evidence:** no disposal request/approval API, roles, batch snapshot, execution command, certificate, reconciliation, or source-owner delete workflow. Eligibility remains only a calculation result. Audit has no approved disposal path and must remain append-only.
- **Missing functionality:** immutable owner-generated candidate snapshot and digest; separate legal/records clearance and delegated authorization; distinct approver/executor/verifier; one-time batch-bound claim; immediate rechecks of current policy, source revision, holds/fence, archive evidence and approvals; owner-local execution; explicit per-item and partial outcomes; destruction certificate; retained evidence under a separately approved policy; independent reconciliation.
- **Likely services/files:** RLC workflow/API/security/event model and database migrations; source-specific owner execution adapters and local evidence; independent reconciliation tool/service; Audit only through an explicitly approved separate evidence strategy that does not mutate `audit_event`.
- **Database/API contract implications:** new state machine, append-only approval/execution evidence, immutable batch snapshot references, single-use authorization claims, owner-specific execute/receipt contracts and certificate schema. No generic cross-service delete endpoint or direct cross-service DB access.
- **Security requirements:** clearance and senior authorization; least privilege; separation of requester, approver, executor and verifier; authenticated identities and revocation; batch/digest-bound approvals; MFA/approved privileged-access controls; no approval reuse; full retained audit evidence.
- **Failure/concurrency:** hold inserted after snapshot; changed policy/source batch; stale/replayed claim; owner timeout; concurrent command; partial irreversible deletion; duplicate certificate; archive proof invalidation; Audit records included in a batch; backup/replica mismatch. Any uncertainty invalidates authorization; represent partial execution honestly and stop/reconcile remaining work.
- **Automated tests/acceptance:** authorization denial; SoD enforcement; snapshot/approval binding; stale policy/fence invalidation; hold-vs-execution race; idempotent one-time claim; partial failure and certificate reconciliation; prove excluded/held records remain; no Audit mutation path; preserve evidence and independent verifier output.
- **Dependencies/blockers:** WP-1 through WP-4, explicitly defined D/F and ARC-08/DSP-06 traceability, formal Legal/Records Management validation and delegated approval authority, operational source-owner contracts, archive/backup evidence. Implementation of dry-run and state machine can proceed; irreversible execution cannot be enabled before formal approvals and operational evidence.
- **Proceed under provisional baseline:** **Yes** for dry-run, clearance modeling, and non-destructive authorization validation. **No** for irreversible production execution.

### WP-6 — Operational readiness and S8-07 assurance

- **Decisions addressed:** `DB-01`, `OPS-01/02`, `SEC-01`, `TEST-01`, all relevant S8-07 criteria.
- **Existing implementation/evidence:** RLC V5 checks separate non-superuser runtime/migrator roles and grants restricted permissions; Testcontainers checks those controls. Audit has corresponding runtime role/append-only tests. READMEs identify external credentials, broker ACLs, HTTPS, monitoring and deployment needs.
- **Missing functionality/evidence:** production DBA creation/ownership verification and credential delivery; broker accounts/ACLs; deployed workload identities/TLS; archive/key provisioning; alert/retention/recovery runbooks; privacy/security review; current focused tests and regression after implementation; live owner-to-RLC/Audit integration assurance; S8-07 evidence matrix and explicit acceptance gate.
- **Likely services/files:** infrastructure deployment, RLC/Audit configuration and service READMEs/runbooks, owner services, test suites and Stage 8 verification evidence. Gateway route allowlist remains separate and pending its own approval.
- **Database/API contract implications:** provision production DB roles/ownership without broad privileges; verify migrations in deployment environment. No schema/API change is inherent to the assurance package, but each earlier WP's contract must be validated.
- **Security requirements:** production secret management, least privilege, TLS, privileged access, audit logging, privacy-safe health/metrics, restore/reconciliation controls and controlled owner/superuser bypass.
- **Failure/concurrency:** role misconfiguration, migration owner mismatch, credential rotation outage, broker partition/backlog/dead-letter, unready Identity, service failover, alert loss, recovery failure, and replay after restore.
- **Automated tests/acceptance:** current focused RLC/Audit PostgreSQL tests, Identity/common-web and full reactor after code work; deployment smoke and permission proof; owner-to-RLC integration; security/privacy regression; operational runbook and recovery evidence; report exact counts, failures/errors/skips and scope. No live E2E claim without running the deployed multi-service path.
- **Dependencies/blockers:** WP-1–WP-5 implementation; actual DBA, broker, secret/key, archive and environment provisioning; final security/operations review. Historical 2026-11-05 reports cannot close current S8-07.
- **Proceed under provisional baseline:** **Yes** for local automated tests and infrastructure/runbook design. **No** for production readiness or S8-07 acceptance until actual provisioning and final evidence.

## 7. Stage readiness

### Project-level decision blockers removed

- The project may proceed without waiting for RRA to select safe technical defaults for source ownership, fail-closed behavior, non-destructive evaluation, separation of eligibility from disposal authorization, and no cross-service database access.
- Provisional defaults can guide internal contract, state-machine, and test design.
- RRA review is not a prerequisite for implementing non-destructive foundations or test doubles.

### S8-06 implementation blockers remaining

- The per-ID provisional-decision-to-acceptance crosswalk is incomplete.
- Source-owner evidence contract/ingestion and source adapters are absent.
- Evaluation lacks trusted evidence, cross-category maximum, stable provenance and recalculation history.
- No persisted legal-hold lifecycle or source-owner enforcement exists; the H.4 fence protocol is not adopted into S8-06B or implemented.
- No archive destination/workflow, transfer/integrity/recovery/reconciliation exists.
- No disposal workflow, approval separation, source-owner execution, certificate or independent reconciliation exists.
- `S8-06D` and `S8-06F` are undefined; `S8-06E-03` remains an unapproved proposal; ARC-08/DSP-06 remain traceability gaps.
- Formal policy schedule validation, approval, authority and effective date remain outstanding. This prevents operational use of proposed periods, but does not block technical work on safe foundations.

### S8-07 assurance and operational blockers

- Production DBA role and object ownership provisioning, secrets, broker credentials/routing ACLs, workload identity/TLS, archive/key provisioning and operational monitoring are not proven by local tests.
- Current reports are historical (2026-11-05); no fresh post-2026-11-08 RLC verification or final integrated S8 regression evidence was established in this audit.
- No live multi-service lifecycle E2E exists; RLC PostgreSQL tests mock Identity.
- Required security/privacy evidence, runbooks, recovery proof, current focused tests, regression results and S8-07 acceptance matrix remain incomplete.

### Work that can proceed now

- P0 traceability and checklist-definition closure by Project CTO/checklist owner.
- Provider-neutral source evidence contract and fail-closed ingestion, after project/service-owner mapping.
- Non-destructive retention computation and provenance tests using synthetic, explicitly non-production policies.
- Hold persistence and conservative fail-closed enforcement, using test-only actors/evidence and no irreversible operation.
- Archive manifest/verification and restore harnesses against local/test storage without real records.
- Disposal dry-run, batch-snapshot and authorization-state modeling without physical destruction.
- Automated security, PostgreSQL privilege, concurrency, replay and privacy tests.

### Work requiring provisioning or formal RRA validation

- Production policy activation with actual retention durations requires formal Legal/Records Management validation and management approval.
- Production source-owner lifecycle integrations require owner identity/credentials, approved operational ownership, and source-side acknowledgement.
- Real archive transfers require a selected and operationally provisioned destination, key ownership, data-residency/access decisions, and validation of preservation policy.
- Operational legal holds require authoritative instruction/authority evidence and owner deployment/acknowledgements.
- Irreversible disposal requires formally authorized policy, clearance/delegation, operational separation of duties, owner-side controls, archive/hold proof, certificate and independent reconciliation. S8-06B does not authorize it.
- S8-07 requires production provisioning evidence, current regression/integration test results, security/operations evidence, and completion of applicable S8-06 requirements.

## 8. Required status synchronization (not performed in this audit)

The central roadmap still describes S8-06 as blocked pending “formal RRA decisions” and describes all 36 S8-06A records as open, while S8-06B records provisionally adopted project decisions. These statements can coexist only if the documentation distinguishes:

1. project-level engineering baseline: provisionally adopted;
2. formal RRA policy/legal/records validation: pending;
3. implementation and test completion: pending;
4. production provisioning and operational readiness: pending.

The current documents do not consistently use this four-part status model. The Stage 8 summary should not be changed to complete: S8-06 remains blocked for implementation and/or formal operational acceptance, S8-07 remains blocked, and no Stage 8 acceptance is claimed. This consistency correction is reported here only; existing documentation was not edited.

## 9. Verification conclusion

| Verification question | Result |
|---|---|
| Are all original 36 S8-06A IDs preserved exactly once in the authoritative Section I inventory? | **Yes** — 36 rows, 36 unique IDs. |
| Does S8-06B list all 36 original IDs? | **Yes** — 36 individual rows. |
| Does every ID have a sufficiently specific provisional outcome, rationale, owner, implementation consequence and acceptance criteria? | **Partially** — common owner/status and broad outcomes exist; per-ID implementation and acceptance mapping remains incomplete. |
| Are ARC-08 and DSP-06 handled without altering the 36-ID inventory? | **Yes** — explicitly identified as gaps; no extra original inventory row added. |
| Is the proposed HOLD-01..05 lifecycle coherent? | **Yes as a proposal** in H.4; not adopted in enough detail by S8-06B and not implemented. |
| Is retention evaluation ready for operational decisions? | **No** — calculator foundation exists; evidence integration, cross-category evaluation, provenance and approval are missing. |
| Are archive and disposal operational safeguards implemented? | **No** — design proposals only; no transfer, hold, or disposal workflow. |
| Are S8-06D, S8-06E-03 and S8-06F unambiguous and implementation-ready? | **No** — D/F lack authoritative definitions; E-03 is only an unapproved proposal. |
| Have legal retention periods or unsupported legal references been invented in S8-06B? | **No** — existing proposed schedule remains unvalidated and has no invented citation. |
| Is S8-06 complete? | **No.** |
| Is S8-07 complete or operationally ready? | **No.** |

## 10. Validation record

The 36-ID inventory check was read-only and used Node.js against the existing register and S8-06B file. Actual output:

```text
Inventory rows=36; missing/duplicate IDs=none
S8-06B individual rows=36; missing/duplicate IDs=none
```

An earlier heading-only check was not used as the inventory source because 31 original decisions are represented as rows in Section I, not `####` headings; the detailed HOLD-01 through HOLD-05 briefs are separate supporting material. This audit did not execute Maven, service tests, or the full reactor.

Validation command: `git diff --check`. Exit code: **0**. The report's trailing-whitespace check (`grep -nE '[[:blank:]]+$' backend/docs/s8-06c-decision-baseline-verification.md`) returned no matches after cleanup. Because the report is new and untracked, the separate whitespace check is necessary; `git diff --check` alone does not include untracked files.

## 11. Scope confirmation

This S8-06C task changed no application code, schemas, migrations, APIs, permissions, events, or existing documentation. It does not approve any RRA policy, invent retention periods, enable production transfer/holds/disposal, add ARC-08/DSP-06 to the original 36, or claim live multi-service E2E. The Stage 8 roadmap and decision registers were inspected but not edited.
