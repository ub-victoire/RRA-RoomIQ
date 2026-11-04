# S8-06 Audit Retention, Archiving and Disposal Readiness Register

## Purpose and status

This is the single implementation-readiness and decision register for S8-06. It records repository evidence and the proposed requirements supplied in “ROOMQI – Audit Records Retention, Archiving and Disposal Policy”. It does not constitute policy approval, legal advice, production-control evidence, or authorization to implement retention or disposal.

The supplied policy describes proposed operational settings and mandatory controls. RRA's authorized Legal and Records Management functions must validate the settings. Formal management approval, approval reference, policy version, effective date, and responsible approving authority are not evidenced. Accordingly, **S8-06 is BLOCKED**. S8-07 remains **PARTIALLY VERIFIED / BLOCKED**; neither it nor Stage 8 is complete.

Statuses used below:

- **OPEN** — a required decision or evidence has not been supplied.
- **BLOCKED** — dependent on an unresolved policy, approval, or prerequisite.
- **PARTIALLY VERIFIED** — repository evidence supports part, but not all, of the requirement.
- **READY** — evidence is sufficient to proceed to the next authorized gate, not evidence that the final requirement is complete.

No item is represented as READY for implementation. “Responsible authority / owner” names an accountable role where known; a role described as “to be designated” is not an assigned individual or organization.

## RRA-provided proposed schedule

The following values and start points are recorded as supplied. They are proposed operational settings, not independently verified statutory periods and not a formally approved or binding policy.

| Category | Proposed period | Proposed start point |
|---|---|---|
| Access/authentication | 2 years | Original event timestamp |
| User activity/system administration | 5 years | Original event timestamp |
| AI decisions/recommendations | 5 years | Later of decision date or transaction closure |
| Configuration/change records | 5 years | Implementation date |
| Security incidents | 5 years | Formal investigation closure |
| Financial/tax records | Applicable legislation and RRA schedules | As determined by applicable legislation and RRA schedules |
| Legal/regulatory records | Applicable legislation and RRA schedules | As determined by applicable legislation and RRA schedules |
| Routine backups | 1 year, unless a longer requirement applies | Backup creation date |

When multiple approved retention requirements apply, the longest applicable period governs. A valid legal hold takes precedence over routine expiry and suspends disposal while active. Hold release requires authorized written instructions; release does not shorten any longer applicable retention period. These are recorded policy requirements, not implemented controls. Applicable legislation, detailed scope, precedence calculation, authority and effective policy approval remain decision dependencies.

## A. Policy and governance decisions

| ID | Decision / dependency | Why required | Evidence currently available | Responsible authority | Status | Evidence that closes it |
|---|---|---|---|---|---|---|
| GOV-01 | Validate proposed periods and categories against applicable legislation and RRA records schedules. | The supplied policy expressly requires validation; the schedule is not an independently verified statutory schedule. | Supplied proposed schedule; the SRS says final periods are validated with RRA. No legal validation evidence is present in the repository. | RRA authorized Legal and Records Management functions; names to be designated. | BLOCKED | Written validation identifying applicable laws/schedules, covered records, and any changes to the proposed schedule. |
| GOV-02 | Formal RRA management approval, including approval reference, policy version, effective date, and responsible approving authority. | Required to make policy operational and establish the authoritative version and effective boundary. | The supplied document is described as proposed settings; no approval reference, version/effective approval record, or authority evidence is available here. | RRA authorized management/policy owner; to be identified. | BLOCKED | Approved policy artifact or written approval record containing each listed governance datum. |
| GOV-03 | Confirm scope and definitions for each record category and longest-applicable-period calculation. | Categories can overlap; a deterministic rule is necessary to calculate eligibility without premature disposal. | Proposed categories and longest-applicable rule are supplied; repository has no retention classifier or policy evaluator. | RRA Legal/Records Management; technical owner to be designated for implementation. | BLOCKED | Approved category definitions, overlap examples, calculation rules, and boundary/time-zone conventions. |
| GOV-04 | Define authority for statutory and RRA schedules, updates, and conflicts. | RoomIQ must not invent legal periods or silently use stale schedules. | Schedule says applicable legislation and RRA schedules; no exact legislation, schedule, update source, or escalation procedure is in repository. | RRA Legal/Records Management. | OPEN | Written schedule references, authoritative source, update owner, and conflict/escalation procedure. |

## B. Record-category source mapping

The mapping distinguishes source-owned business records, operational records, producer audit events, and Audit persistence. It does not classify ordinary logs as authoritative Audit records. Original event time and ingestion time are separate concepts: Identity envelopes carry `timestamp`, which Audit maps to `audit_event.occurred_at`; `audit_event` has no dedicated ingestion timestamp. Identity outbox `created_at` is an outbox creation time, not an Audit ingestion time.

| ID / category | Proposed period and start | Authoritative source service/system | Existing verified event/entity/table | Original event timestamp | Ingestion timestamp | Missing source or contract / technical owner | Readiness |
|---|---|---|---|---|---|---|---|
| SRC-01 Access/authentication | 2 years; original event timestamp | Identity for Identity authentication events; Audit owns its persisted copies. | `identity_audit_outbox`; versioned Identity event envelope; Audit `audit_event`. Verified in Identity event DTO and Audit processor. | Available as event `timestamp`; persisted to `audit_event.occurred_at`. | Not persisted in `audit_event`. Identity outbox `created_at` is available but is not Audit ingestion time. | Identity owns source event creation; Audit owns consumed copy. Confirm exact original-time boundary semantics, clock treatment and late-arrival handling. | PARTIALLY VERIFIED |
| SRC-02 User activity/system administration | 5 years; original event timestamp | Identity for supported Identity administration; each domain service for its own activity/change facts. Audit owns only supported consumed copies. | Identity outbox/events and Audit `audit_event` for supported events. The S8-01 action-owner matrix documents incomplete facts/actions. Other service events are not automatically Audit events. | Available for supported Identity events; per-source completeness is partial. | Not persisted in Audit. No common ingestion-time field exists. | Identity and each relevant service own their event facts. Technical owners must identify missing source actions and contracts; Audit must not read other services' databases. | PARTIALLY VERIFIED |
| SRC-03 AI decisions/recommendations | 5 years; later of decision date or transaction closure | No authoritative RoomIQ source service/system identified. Analytics is only an application shell in inspected code. | No AI decision/recommendation entity, table or event contract found. | Not available. | Not available. | No source owner is established. RRA and technical leadership must designate an owning service/system and approved minimized record contract. | BLOCKED |
| SRC-04 Configuration/change records | 5 years; implementation date | Configuration runtime is service/environment owned; business configuration is owned by individual domain services. | Some service-owned state and Identity audit events exist. No common configuration history/category is present. | Available only for specific source events/fields; no universal implementation timestamp. | No common ingestion timestamp or configuration audit register. | Each service technical owner must define source-owned change evidence and “implementation date”; operational configuration management owner is not identified. | PARTIALLY VERIFIED |
| SRC-05 Security incidents | 5 years; formal investigation closure | No authoritative RoomIQ incident/investigation system identified. | No case entity, incident table, event contract, or formal closure timestamp found. Operational logs are not an incident case record. | Not available as a governed incident record. | Not available. | Incident-management system and responsible security/technical owner must be identified; no source is assumed. | BLOCKED |
| SRC-06 Financial/tax records | Applicable legislation and RRA schedules; start under those requirements | No dedicated RoomIQ financial/tax system of record identified in inspected backend services. | No dedicated financial, tax, invoice, or payment record source found. | No statutory classification/start timestamp found. | Not available. | RRA must confirm applicability and identify the system-of-record owner and law/schedule. | BLOCKED |
| SRC-07 Legal/regulatory records | Applicable legislation and RRA schedules; start under those requirements | No RoomIQ case/matter-management system identified. | No legal/regulatory record entity/table or event contract found. | No governed matter closure timestamp found. | Not available. | RRA must confirm applicability, records owner/system, schedule and closure semantics. | BLOCKED |
| SRC-08 Routine backups | 1 year unless longer requirement applies; backup creation date | Production backup system/owner is not identified. Local infrastructure contains development PostgreSQL/RabbitMQ/Redis services and volumes only. | No production backup catalog, backup entity, schedule, or lifecycle configuration found. | Not available as a RoomIQ-managed backup record. | Not available. | Operations/hosting owner and RRA must identify backup system, lifecycle, longer-retention rules and evidence. | BLOCKED |

## C. Archive architecture decisions

The requirements below are not evidence of production deployment. Existing Audit append-only controls concern the primary `audit_event` table only; they do not prove archive controls.

| ID | Decision / dependency | Why required | Evidence currently available | Responsible authority / owner | Status | Evidence that closes it |
|---|---|---|---|---|---|---|
| ARC-01 | Select and approve the centralized archive repository, service owner, data residency and access boundary. | RRA requires an approved centralized repository; none is identified in the repository. | No archive service, provider, destination, or transfer path found. | RRA policy owner and designated infrastructure/records technical owner. | BLOCKED | Written repository decision, ownership/RACI, location, access boundary, and deployment evidence. |
| ARC-02 | Approve archive record format, required metadata, and source/record linkage. | Records must remain interpretable, traceable and recoverable without changing frozen event contracts by assumption. | Current Audit event envelope and `audit_event` schema exist; no archive format/manifest contract exists. | RRA Records Management and Audit/records technical owner. | OPEN | Approved format/metadata specification and mapping from each owning service's records. |
| ARC-03 | Verify encryption at rest (AES-256 or equivalent) and in transit (TLS 1.2 or higher), including key ownership and rotation. | Explicit supplied RRA control; prevent exposure and establish key lifecycle. | No archive or production security configuration/evidence in repository. | Designated infrastructure/security owner; RRA approves policy. | BLOCKED | Approved cryptographic/key-management design and deployment evidence for archive and transfer. |
| ARC-04 | Define archive RBAC, least privilege and MFA for privileged users. | Restrict archive read/admin operations and privileged access. | Audit Search `AUDIT_READ` authorization exists for the API, but it does not define archive access or prove MFA. | RRA policy owner and Identity/security/infrastructure technical owners to be designated. | BLOCKED | Approved role/permission matrix and tested deployed RBAC/MFA evidence. |
| ARC-05 | Define integrity verification and tamper evidence for archived records. | Detect alteration and support evidentiary integrity. | Primary Audit table uses `@Immutable`, mutation-rejection triggers and runtime SELECT/INSERT grants when provisioned. Owner/superuser bypass remains; no cryptographic tamper evidence or archive integrity process exists. | Audit and infrastructure/security technical owners; RRA Records Management. | PARTIALLY VERIFIED | Approved archive integrity method, verification cadence/evidence, tamper-response procedure, and test results. |
| ARC-06 | Approve encrypted backup lifecycle and documented recovery tests for archive and source records. | Archive/backups must survive failure; recovery must preserve integrity and applicable holds/retention. | Local compose volumes only; no production backup lifecycle or archive recovery evidence found. | Operations/hosting owner to be designated; RRA approves lifecycle. | BLOCKED | Approved lifecycle, encrypted-backup evidence, recovery runbook, and successful restore/reconciliation exercise. |
| ARC-07 | Establish continuous monitoring, alerting, and access evidence. | Detect unauthorized access, integrity failures, transfer/recovery errors and backlog. | Audit exposes health/info; no archive or retention metrics/monitoring evidence found. | Operations/security owner to be designated. | OPEN | Approved monitoring signals/retention of access evidence, alert ownership, and deployment/test evidence without sensitive payload exposure. |
| ARC-08 | Design archive migration, reconciliation, duplicate/missing detection, and rollback/recovery. | Prevent loss or duplication during transfer and provide auditable evidence. | No archive movement, migration manifest, reconciliation or rollback process exists. | Audit and source-service technical owners; RRA Records Management. | BLOCKED | Approved per-source transfer/reconciliation design and tested completeness/integrity/recovery evidence. |

## D. Legal-hold decisions

| ID | Decision / dependency | Why required | Evidence currently available | Responsible authority / owner | Status | Evidence that closes it |
|---|---|---|---|---|---|---|
| HOLD-01 | Determine authorized hold initiator, approver, scope, written instruction and release authorities. | A hold must be valid, attributable, bounded and releasable only by an authorized party. | No hold API, entity, register, role, or instruction workflow found. | RRA Legal/Records Management; authorized roles to be designated. | BLOCKED | Approved authority matrix and written hold/release instruction format and workflow. |
| HOLD-02 | Define record identification, scope propagation and durable hold register/evidence. | All in-scope copies, archive versions and backups must be discoverable and protected. | No category/source registry or legal-hold record exists. | Records-owning service technical owners and RRA Records Management. | BLOCKED | Approved identifiers/scope rules, hold register owner, evidence fields and reconciliation requirements. |
| HOLD-03 | Ensure automated deletion is suspended for active, uncertain or conflicting holds. | Prevent disposal of legally protected records, including during delayed/failed status updates. | No deletion scheduler exists; no hold-state mechanism or suspension controls exist. | Audit and each records-owning service technical owner, after RRA approval. | BLOCKED | Tests/evidence proving disposal cannot proceed when a hold is active, unavailable, stale or uncertain. |
| HOLD-04 | Resolve multiple/overlapping holds, longer retention and post-release eligibility. | A released hold must not shorten another hold or the longest applicable retention period. | Longest-applicable and hold-precedence rules are supplied; operational conflict semantics are not implemented. | RRA Legal/Records Management. | OPEN | Written precedence/conflict rules and reviewed boundary examples covering hold, release, and longer schedule. |

## E. Disposal governance

| ID | Decision / dependency | Why required | Evidence currently available | Responsible authority / owner | Status | Evidence that closes it |
|---|---|---|---|---|---|---|
| DSP-01 | Approve expiry eligibility calculation, using the authoritative start point and longest applicable period. | Records must not be disposed before every applicable approved period expires. | Audit stores producer `occurred_at`; no ingestion timestamp, category classifier, policy evaluator, or eligibility calculation exists. | RRA Legal/Records Management and source technical owners. | BLOCKED | Approved rule semantics and tested calculations for all applicable categories and boundary cases. |
| DSP-02 | Require Records Management review and Legal/Compliance clearance before disposal. | Validate schedule, holds, disputes, and regulatory constraints before irreversible action. | No review or clearance workflow exists. | RRA Records Management and Legal/Compliance authorities; named delegates to be designated. | OPEN | Approved clearance workflow, responsibility matrix and retained authorization evidence. |
| DSP-03 | Define senior-management authorization and segregation between authorization and execution. | Prevent unilateral or unauthorized destruction. | No disposal roles, separation-of-duties control, or disposal path exists. | RRA authorized management/policy owner. | BLOCKED | Approved delegation matrix and tests/evidence showing distinct authorized identities/roles perform approval and execution. |
| DSP-04 | Approve secure-destruction method and treatment of primary, archive, replicas and backups. | A disposal instruction must have a defined scope and verifiable outcome without violating backup/recovery rules. | No destruction method or replica/backup treatment is defined. | RRA Legal/Records Management and technical owners. | BLOCKED | Approved method, scope, exceptions, backup expiry handling, and security review. |
| DSP-05 | Define destruction certificate and independent verification/reconciliation. | Establish accountable evidence that only authorized records were destroyed and that excluded/held records remain. | No certificate format, independent verifier, or reconciliation evidence exists. | RRA Records Management; verifier independent of authorizer/executor. | OPEN | Approved certificate contents, verification authority, reconciliation procedure, and test evidence. |
| DSP-06 | Define disposal audit evidence and its own retention/source. | The disposal action and authorization must themselves be traceable without circular deletion. | No disposal action/event contract or evidence record exists. | RRA Records Management and Audit/source technical owners. | BLOCKED | Approved disposal evidence contract, ownership, retention rule and security/privacy review. |

## F. Technical implementation dependencies

### Existing and verified

- **Identity producer and Audit consumer:** Identity has a transactional `identity_audit_outbox` with an event `timestamp`; Audit's Identity processor persists that source timestamp as `audit_event.occurred_at` and verifies idempotent replay. Evidence: [IdentityAuditEvent.java](../identity-service/src/main/java/rw/rra/roomiq/identity/domain/dto/IdentityAuditEvent.java), [IdentityAuditOutboxEntry.java](../identity-service/src/main/java/rw/rra/roomiq/identity/domain/entity/IdentityAuditOutboxEntry.java), and [IdentityAuditEventProcessor.java](../audit-service/src/main/java/rw/rra/roomiq/audit/domain/IdentityAuditEventProcessor.java). PostgreSQL and RabbitMQ integration evidence is in `IdentityAuditPersistencePostgresTests`, `AuditEventSearchPostgresTests`, and `IdentityAuditRabbitIntegrationTests` under `audit-service/src/test/java/rw/rra/roomiq/audit`.
- **Audit primary record and append-only controls:** Audit V1 has `audit_event.occurred_at` and no ingestion timestamp/category/policy fields. V2 rejects UPDATE/DELETE/TRUNCATE and grants the configured runtime principal SELECT/INSERT; Hibernate uses `@Immutable`. This is not archive tamper evidence and does not protect against privileged owner/superuser bypass. Evidence: [V1__audit_event.sql](../audit-service/src/main/resources/db/migration/V1__audit_event.sql), [V2__protect_audit_event_append_only.sql](../audit-service/src/main/resources/db/migration/V2__protect_audit_event_append_only.sql), and [AuditEvent.java](../audit-service/src/main/java/rw/rra/roomiq/audit/domain/AuditEvent.java).
- **Audit Search authorization:** S8-05 implements the approved Identity-authoritative Search API and `AUDIT_READ`. It is not archive RBAC, hold authorization, or disposal authorization. Search API contract remains frozen.
- **Notification template audit path:** Approved template-audit events are consumed by Audit. This does not provide a general source contract for all RRA categories.
- **Tests:** Existing Audit event codec, persistence, immutability, search, listener, RabbitMQ and Testcontainers/PostgreSQL tests verify current behavior. They do not prove any retention, archive, hold or disposal requirement.

### Partially implemented

- **Identity access/activity events:** Some authentication and administration facts have an Identity event and Audit path; coverage and classification are incomplete as documented in the [S8-01 action-owner matrix](./audit-action-owner-matrix.md).
- **Original event time:** Available for supported producer events and stored as `occurred_at`; the contract does not define an ingestion timestamp. Confirm source clock, late delivery and authoritative boundary semantics before a retention evaluator is designed.
- **Append-only integrity:** Primary database protections are verified in tests, conditional on correct non-owner production role provisioning. They are not cryptographic proof and do not provide archive immutability.
- **Configuration changes:** Some business mutations emit Identity audit events; source-owned configuration/history coverage is incomplete and fragmented.

### Missing

- Retention categories, approved policy configuration/version/effective date, category classifier, eligibility evaluation, scheduler, and retention-boundary tests.
- Separate ingestion timestamp in Audit persistence, if required by the approved design; this is a design question, not an approved schema change.
- Central archive/repository, format/manifest, transfer, reconciliation, encryption/key management, archive RBAC/MFA, monitoring, integrity verification and recovery.
- Legal-hold register, instruction/release workflow, conflict handling and verified disposal suspension.
- Disposal authorization/execution separation, secure-destruction mechanism, destruction certificate, independent verification, and disposal audit evidence.
- AI decision/recommendation source and traceability data; security incident/investigation records; financial/tax and legal/regulatory system-of-record contracts; and production backup catalog/lifecycle evidence.

### Awaiting RRA decision or approval

- GOV-01 through GOV-04, ARC-01 through ARC-08, HOLD-01/HOLD-02/HOLD-04, and DSP-02 through DSP-06 require RRA policy, Legal/Records Management, management, or operational decisions as specified in their rows.
- Formal S8-06 policy approval and effective policy evidence are prerequisites to implementation. Proposed periods alone are insufficient.

### Awaiting technical-owner decision

- Designate records-owning service/system owners for each category, particularly AI decisions, security incidents, financial/tax, legal/regulatory and backups.
- Confirm authoritative timestamp mapping and required ingestion timestamp evidence.
- Determine service-local implementation boundaries and operational control ownership after policy approval.
- The technical design in Section H is a proposal only. Its schema, API and event shapes are not approved fields, migrations, indexes or contracts.

## G. Acceptance criteria and test evidence

The following evidence is required after policy approval and implementation authorization. Tests must exercise service-owned records and must not imply that unapproved disposal behavior is authorized.

| ID | Acceptance evidence required | Current evidence / status |
|---|---|---|
| TST-01 Retention boundaries | Deterministic tests at just-before, exact-boundary and just-after expiry for each approved category, period, start point and applicable time convention. | No retention evaluator or tests exist; BLOCKED pending GOV-01–04 and policy approval. |
| TST-02 Original vs ingestion time | Tests prove the approved start point is used, distinguish producer original event time from later Audit ingestion, and cover late/out-of-order delivery and clock behavior. | Source `timestamp` maps to `occurred_at`; no Audit ingestion timestamp is stored. Semantics and any need for separate ingestion evidence are open. |
| TST-03 Overlapping requirements | Tests prove the longest applicable approved period is retained across category/schedule overlap and applicable-law/RRA schedule inputs. | Rule supplied; no classifier, schedule integration, or evaluator exists. |
| TST-04 Legal-hold precedence/release | Tests prove active/uncertain/conflicting holds prevent disposal, release requires authorized written evidence, and post-release longest-retention eligibility remains enforced. | No hold register or mechanism exists. |
| TST-05 Archive integrity/recovery | Tests verify encrypted transfer/storage requirements, integrity/tamper detection, archive reconciliation, restore/recovery and access restrictions. | No archive, transfer, recovery procedure, or deployed control evidence exists. |
| TST-06 Unauthorized disposal | Security tests deny disposal without required Legal/Records clearance and management authorization, and fail closed on missing/uncertain hold or eligibility evidence. | No disposal operation exists; no such test is authorized or present. |
| TST-07 Segregation of duties | Tests/evidence prove authorizer and executor are distinct authorized identities/roles and independent verification is separate. | No roles/workflow exist. |
| TST-08 Disposal evidence | Tests verify approved scope, eligibility inputs, hold/clearance checks, destruction certificate, independent reconciliation, and retained disposal audit evidence. | No certificate, disposal process, verifier or evidence contract exists. |
| TST-09 Existing integrity regression | Re-run Audit append-only and role-separation PostgreSQL tests after any approved changes; verify no path weakens existing protections. | Existing focused tests verify current trigger/role behavior; production DBA provisioning remains outstanding. |
| TST-10 Cross-service regression | After each authorized implementation batch, run focused owner-service and Audit/PostgreSQL tests, Identity suite, common-web suite, then full backend reactor; record actual modules, counts, failures, errors and skips. | Prior Stage 8 regressions cover current code only; no retention implementation has been tested. |

## Decision closure and stage gate

1. Close governance decisions and obtain RRA Legal/Records Management validation.
2. Record formal management approval, reference, version, effective date and approving authority.
3. Assign technical owners and resolve archive, hold, backup, access, recovery and disposal architecture decisions.
4. Present any exact schema/API/event proposal separately for explicit approval before editing those artifacts.
5. Only after explicit implementation authorization, implement service-owned controls and tests; preserve append-only protections and verify production controls with operational evidence.
6. S8-06 remains blocked until its approved controls and required regressions pass. S8-07 remains partially verified/blocked until S8-06 and all other required Stage 8 gates are resolved. No Stage 8 acceptance is implied by this register.

## H. Technical implementation design (proposal; not authorized)

This section resolves the earlier chat-only design ambiguities and is the durable technical proposal associated with this register. It does not create an approved architecture decision, policy, schema, API/event contract, or permission to implement. The policy schedule remains proposed until GOV-01/GOV-02 close. Every schema and interface below is illustrative PostgreSQL/service design and requires explicit approval before implementation.

### H.1 Ownership and service boundaries

| Concern | Proposed owner | Boundary |
|---|---|---|
| Policy authority | RRA management, with Legal and Records Management validation | RRA's approved policy artifact is authoritative. No RoomIQ service can approve, interpret, or change it. |
| Approved policy-version registry and lifecycle workflow | A new internal **Records Lifecycle Control (RLC)** service, subject to architecture approval | RLC stores immutable policy approval evidence, hold instructions and coordination state, approvals, and references/digests. It does not own source records, classify their business meaning, or connect to another service's database. It is not an archive provider. |
| Record classification, authoritative timestamps, lifecycle enforcement, source-side archive manifests/transfer evidence, and disposal execution evidence | Each service/system that owns the record | The owner maps its records to approved categories, applies lifecycle commands locally, resolves bounded selection criteria, and retains its own evidence. A service may refuse an unsupported category or instruction. |
| Audit records | Audit Service | Audit owns consumed audit copies only. Identity and Notification continue to own their source facts/outboxes. Audit lifecycle metadata must be separate from `audit_event`; current `@Immutable` and database mutation-rejection protections must not be weakened. |
| Holds across source services | RLC is the control-plane authority; each source owner is authoritative for application to its own records | RLC keeps the approved instruction and per-owner acknowledgement. Each owner maintains local hold-to-record membership and enforces the block. A central hold does not directly update source data. |
| Archive manifest and transfer evidence | Source owner for each service's package; Audit for its own audit records | Source owner produces and signs its own manifest and transfer evidence. An independent verifier checks the destination. RLC stores only references, digests and workflow status. |
| Disposal authorization | RLC workflow, after required RRA-designated approvals | Approval applies to a source-owner-produced, immutable batch snapshot/digest. Authorization is not a deletion command or a reusable permit. Approvers cannot execute or independently verify the same batch. |
| Destruction and its primary evidence | Source owner performs its own approved operation and records the result; RLC keeps the approval/evidence reference | The source owner does not accept a generic cross-service delete. Destruction certificates and per-record outcomes remain source-owned; RLC records their digests/references. |

RLC is a recommendation because there is no lifecycle control-plane service in the inspected repository. Naming, placement, identity permissions, availability model and RRA assignment of approver roles remain decisions. If RRA does not approve a new service, an alternative owner and equivalent cross-service coordination design must be explicitly selected; these responsibilities must not silently be assigned to Audit.

### H.2 Category and source ownership

The classification owner is the service with authority over the record, not Audit merely because it stores a copy. Existing audit envelopes are not evidence that every category is covered.

| Category | Proposed authoritative owner/source and timestamp | Local lifecycle adapter responsibility | Current gap |
|---|---|---|---|
| Access/authentication | Identity for Identity authentication records; source event timestamp (`timestamp`, persisted by Audit as `occurred_at`) | Identity classifies/emits its facts; Audit applies lifecycle only to the persisted Audit copy. | Coverage is limited to emitted events; Audit has no `ingested_at`. |
| User activity/system administration | Identity for Identity actions; each domain service for its own actions. Source's original event timestamp is authoritative. | Each producer owns classification and a durable outbox/contract; Audit consumes only approved contracts. | Action coverage is incomplete; general service activity is not automatically audited. |
| AI decisions/recommendations | **No authoritative service identified.** RRA/technical leadership must designate a source owner before the category is implementable. | Designated owner records decision and required traceability at decision time, then updates/links closure and human-intervention facts without overwriting the original decision. | No AI decision entity, event or owner was found; Analytics is only a shell. |
| Configuration/change records | Each service/system that owns the configuration; `implementation_at` must be supplied by that owner. | Owner distinguishes proposed change from deployed/implemented change and emits the effective implementation fact. | No common change-history source or universal implementation timestamp. |
| Security incidents | **No authoritative incident/investigation system identified.** Security owner and case system remain to be designated. | Incident owner supplies formal investigation closure timestamp and case references. | No incident/investigation record or closure source; ordinary logs are not incident records. |
| Financial/tax | **No RoomIQ source identified.** RRA must identify the applicable external/system-of-record owner and approved schedule. | That owner classifies the record and provides the legally approved start/expiry inputs; RoomIQ must not infer them. | No financial/tax record source or schedule mapping found. |
| Legal/regulatory | **No RoomIQ case/matter source identified.** RRA must identify the authoritative case system and applicable schedules. | The case owner provides bounded record references and approved schedule/closure inputs. | No legal/regulatory case entity, contract or governed closure timestamp found. |
| Routine backups | Production backup system/hosting owner, not a RoomIQ business service. Creation timestamp and backup catalog are authoritative. | Operations adapter enforces backup expiry/hold linkage and reports backup-level evidence. | No production backup owner, catalog, retention configuration or recovery evidence found. |

No producer event or operational log becomes an authoritative audit record without approved classification, source ownership, minimization rules, timestamp semantics, versioned contract and tests.

### H.3 Timestamps, policy selection and deterministic eligibility

Keep the producer's original event time distinct from transport, outbox and Audit ingestion times. For existing Identity events, `timestamp` is producer time and `occurred_at`; `identity_audit_outbox.created_at` is outbox creation time. Notification outbox `created_at` is likewise not Audit ingestion time. If accepted, each owner records its own ingestion/receipt time separately; it must never substitute that value for a category's approved retention start. The Audit consumer should capture `ingested_at` only when it first accepts an event, and preserve it on idempotent replay.

The following are proposed deterministic technical defaults, not legal interpretations. RRA Legal/Records Management must approve or replace them before code exists:

- Treat a supplied “year” duration as calendar years, not a fixed number of days. Compute in UTC; preserve time-of-day. If a target calendar date does not exist (for example February 29), clamp to the final day of that month. Fixed-day rules must be explicitly represented as days, not silently converted.
- Define the retention interval as `[start, expiry)`: eligible at `evaluation_time >= expiry`. Equality at the expiry instant is eligible under this recommendation. Use a trusted UTC clock and store the computed expiry and inputs for reproducibility.
- Choose policy versions by an approved effective-date rule. Recommended default: a record uses the approved version effective at its authoritative event/start date (`effective_from <= date < effective_to`). A later version must not silently shorten an existing expiry. Retroactive extensions or shortening require explicit approval and a recorded recalculation; no retroactivity is inferred.
- Ask the externally authoritative Legal/Records schedule owner to return a versioned rule reference and expiry input. RoomIQ does not parse legislation or decide statutory interpretation.
- If any required start timestamp, closure date, category, source ownership, schedule result, policy version, hold status or owner acknowledgement is missing, conflicting or unavailable, the record is **NOT ELIGIBLE** and is routed for resolution. Unknown categories are never assigned a default period.
- A record may carry multiple approved category/schedule obligations; retain to the latest computed expiry. A legal hold is a separate blocker, not a duration to fold into the expiry timestamp.

```text
eligibility(record, evaluation_time):
    if record.owner is unknown or record.category is unknown:
        return NOT_ELIGIBLE("unclassified or owner unknown")
    if record.original_start_inputs are incomplete:
        return NOT_ELIGIBLE("required authoritative timestamp missing")

    version = approved_policy_for(record.authoritative_start_date)
    if version is absent or not validated/effective:
        return NOT_ELIGIBLE("no applicable approved policy")

    expiries = []
    for each approved rule applicable to record.category:
        start = rule's authoritative start value from the record owner
        if start is unresolved:
            return NOT_ELIGIBLE("rule start unresolved")
        if rule is an external statutory/RRA schedule:
            expiry = approved schedule service/owner result(rule.reference, record)
            if expiry is absent or ambiguous:
                return NOT_ELIGIBLE("schedule unresolved")
        else:
            expiry = add(start, rule.duration, rule.unit, approved calendar semantics)
        expiries.append(expiry)

    if expiries is empty:
        return NOT_ELIGIBLE("no applicable retention obligation")
    retain_until = max(expiries)

    if any applicable hold is ACTIVE, PENDING_BLOCK, CONFLICTED,
       unacknowledged, or has unknown status:
        return NOT_ELIGIBLE("hold or owner state blocks disposal")
    if evaluation_time < retain_until:
        return NOT_ELIGIBLE("retention period not expired")
    return ELIGIBLE(retain_until, policy version, rule references, input digest)
```

Examples under the proposed calendar-year/UTC convention only:

1. A record with a 2-year and a 5-year applicable obligation, both starting `2024-03-10T12:00:00Z`, has the later expiry `2029-03-10T12:00:00Z`; the 2-year rule cannot shorten it.
2. An AI decision at `2024-01-10T09:00:00Z` with transaction closure at `2024-06-15T17:00:00Z` starts at the latter instant; the proposed 5-year expiry is `2029-06-15T17:00:00Z`.
3. If an AI transaction has not closed, the later start is unknown and the record is not eligible. An ingestion timestamp does not fill the gap.
4. A valid hold with an expiry already reached still blocks disposal. Releasing one hold does not clear another active or pending hold; after all holds are released, normal longest-expiry calculation runs again.
5. A proposed 5-year calendar rule beginning `2024-02-29T10:00:00Z` yields `2029-02-28T10:00:00Z` under the recommended leap-day clamp. This behavior and the exact boundary remain pending RRA approval.

### H.4 Legal-hold scope, concurrency and race protocol

#### Scope and authoritative status

- Each written instruction receives an immutable `hold_id`, opaque case/investigation reference, evidence digest, initiator/approver identities, scope-profile identifier/version, received time, and monotonically increasing control sequence. Do not copy case narratives or broad personal data into RLC.
- Support an individual record by owner + record type + source record ID; a multi-record case by a bounded, versioned owner-approved scope profile. No free-form SQL, unbounded query, or cross-service database query is permitted. The owner resolves the profile against its own records and stores the resulting membership locally. For future records, a profile must explicitly define whether it is a continuing predicate and the owner must apply it on record creation; otherwise scope is a snapshot.
- Holds are independent. The local owner stores one relation per `(hold_id, record_id)`; releasing one removes only that relation. A record is blocked while any linked hold is active or unresolved.
- RLC's instruction status is authoritative for authorization/workflow; source-owner local enforcement state is authoritative for whether an individual record is blocked. RLC may report `ACTIVE` only after every identified owner acknowledges application for the bounded scope. Status values include `PENDING_BLOCK`, `ACTIVE`, `RELEASE_PENDING`, `RELEASED`, `REJECTED` and `CONFLICTED`; unknown or stale status fails closed.
- A hold may not be marked `RELEASED` centrally until each affected owner acknowledges release. Delayed/unavailable owners remain pending; release of one hold never releases another.

#### Hold registration vs prepared/queued disposal

1. RLC validates written authority and scope, persists the hold as `PENDING_BLOCK`, increments a monotonic fence for the affected owner/scope, and invalidates every not-yet-executing batch prepared under an earlier fence. This commit is the control-plane ordering point; no new execution may be admitted for that scope.
2. RLC synchronously commands each source owner to apply the hold with `(hold_id, scope_profile/version, fence, idempotency_key)`. Durable outbox/events may retry commands, but events alone are not acknowledgements.
3. Each owner serializes the command and disposal transitions under the same local transaction/lock for affected records. It applies local hold links, cancels `PREPARED`, `AUTHORIZED` or `QUEUED` items that have not started, rejects stale-fence execution requests, and acknowledges only after no queued item can cross into execution.
4. The owner reports already irreversible/in-flight work explicitly. RLC remains `PENDING_BLOCK` and escalates; it must not falsely report the hold active or claim that completed destruction can be reversed. Owner APIs fail closed when RLC is unavailable, the latest fence cannot be established, or local state is stale.
5. A disposal execution must acquire a single-use, batch-bound execution claim against the current fence and owner snapshot. The claim is serialized with hold registration; it cannot be reused or replayed. A hold arriving after an execution has crossed its irreversible boundary cannot undo it. The RRA authority must approve the effective-time/exception treatment for this race; until then the system must stop before irreversible execution and surface the conflict.
6. On release, RLC advances the fence again, sends a separate release command and records per-owner acknowledgements. Existing batch authorizations remain invalid; owners recompute eligibility and holds from current state. A released hold never revives a cancelled disposal batch.

This protocol avoids cross-service transactions and database access. It cannot make an already completed external destruction reversible; that limitation and the formal hold effective-time rule require RRA decision GOV/HOLD closure.

### H.5 Archive and disposal flow

#### Archive

1. Source owner selects eligible records and constructs a deterministic, versioned manifest containing source/service identity, record identifiers or privacy-minimized references, category, authoritative timestamps, source policy version, count/byte totals, per-object digests, whole-manifest digest, creation time, and source signing-key identifier. The package includes only approved record data.
2. Source owner signs the manifest and transfers payload plus manifest over TLS 1.2+ to a repository selected and approved by RRA. The repository, provider, data residency, key owner/rotation and access policy are still undecided.
3. Independent verifier (not the package preparer) validates signature, object count/size, per-record and manifest digests, metadata, source-to-destination reconciliation, access/encryption evidence and that no held record is omitted or altered. A transfer is successful only after this verification and durable acknowledgement; “upload succeeded” is insufficient.
4. Source owner records the manifest and transfer/verification evidence locally. RLC stores source, manifest ID, digest, count and status references only. Reconciliation is repeatable and idempotent; mismatch quarantines the package and blocks disposal.
5. Recovery tests restore a sampled package into an isolated location, verify digest/record counts/metadata and access controls, and record independent evidence. Recovery cadence/targets require approval; no RPO/RTO is invented.

#### Disposal

1. Source owner builds a stable batch snapshot of records whose full retention calculation is eligible, with policy/rule references, timestamp inputs, current hold fence, archive verification evidence where required, record count and digest. Unknowns are excluded and surfaced.
2. RLC records a disposal request and obtains distinct required approvals: Records Management review, Legal/Compliance clearance where applicable, and delegated senior-management authorization. The same identity cannot approve and execute; the independent verifier must differ from both. Required roles/delegations are not assigned yet.
3. Before execution, owner rechecks batch digest, current policy version, all applicable holds, fence, archive verification and approvals. Any change cancels authorization and requires a fresh snapshot and approvals.
4. Owner executes only its own records using the specifically approved secure-destruction method; no generic RLC delete endpoint exists. Each item gets an idempotent outcome. Partial execution is not rolled back by pretending success: completed items remain evidenced, remaining items are blocked/reconciled, and the batch enters `PARTIAL` pending independent review.
5. Owner issues a destruction certificate referencing the approved batch, item count/digest, method/version, execution identity/time, exceptions and result digest. A separate verifier reconciles the certificate to the approved snapshot and confirms excluded/held items remain. RLC stores authorization and evidence references/digests; the source owner retains detailed local evidence.
6. Disposal evidence has its own approved record category and retention rule to avoid circular deletion. Its ownership, period and treatment of replicas/backups must be decided before disposal can be enabled.

#### Audit append-only constraint

Current Audit V2 rejects `UPDATE`, `DELETE` and `TRUNCATE` on `audit_event`, and Audit's runtime role is intended to have only `SELECT`/`INSERT`. No retention or disposal path may bypass these controls. Therefore there is no currently approved or technically implemented way to dispose Audit rows. Any future solution that changes table layout, partitioning, triggers, privileges, encryption or deletion behavior requires a separate exact proposal, security review, RRA approval and migration approval. Until that is approved, Audit records cannot be represented as disposal-ready.

### H.6 AI audit traceability

When and only when an authoritative AI decision source is designated, its source-owned immutable decision record/event should preserve: decision/recommendation identifier and type; model identity/version and effective configuration identifier/version; minimized input references (not unnecessary raw prompt/input); output or approved output reference; original decision timestamp; responsible user or service-account identity; human intervention/review actor, time and outcome; and traceability references to the related transaction/case and closure timestamp. Preserve original output/decision rather than overwriting after intervention; emit a separate linked intervention fact. Retention uses the later of decision time and transaction closure, with unresolved closure fail-closed. Exact fields, sensitive-data policy and contract require the AI owner and RRA approval. Keep AI advisory output separate from backend authorization/business rules.

### H.7 Proposed schema (illustrative only; no migration authorized)

PostgreSQL types below are exact proposal types. Cross-service identifiers are opaque UUIDs with **no cross-database foreign keys**. Each source service owns its schema and local foreign keys. Do not add columns to `audit_event`; preserve it unchanged and append-only. Table/field proposals require approval of exact names, constraints and ownership before migration.

#### RLC control-plane database

| Table / field | Type | Nullability / constraint |
|---|---|---|
| `lifecycle_policy_version.policy_version_id` | UUID | PK |
| `version_label` | VARCHAR(64) | NOT NULL, UNIQUE |
| `document_sha256` | CHAR(64) | NOT NULL |
| `approval_reference` | VARCHAR(128) | NULL while draft; required before APPROVED |
| `policy_version` | VARCHAR(64) | NULL while draft; required before APPROVED |
| `effective_from` | TIMESTAMPTZ | NULL while draft; required before APPROVED |
| `effective_to` | TIMESTAMPTZ | NULL; if set, must be `> effective_from` |
| `approving_authority_ref` | VARCHAR(128) | NULL while draft; required before APPROVED |
| `approved_by_subject` | VARCHAR(128) | NULL while draft; required before APPROVED |
| `approved_at` | TIMESTAMPTZ | NULL while draft; required before APPROVED |
| `legal_validation_ref` | VARCHAR(128) | NULL until evidence supplied |
| `records_validation_ref` | VARCHAR(128) | NULL until evidence supplied |
| `status` | VARCHAR(16) | NOT NULL; CHECK `DRAFT`, `APPROVED`, `SUPERSEDED`, `WITHDRAWN`; approved content immutable; status transition recorded in control event |
| `created_at` | TIMESTAMPTZ | NOT NULL |

Index: `(status, effective_from, effective_to)`. Approved rows must have all approval/validation references required by the approved governance process; no version becomes active solely because it exists in the database.

| Table / field | Type | Nullability / constraint |
|---|---|---|
| `lifecycle_policy_rule.rule_id` | UUID | PK |
| `policy_version_id` | UUID | NOT NULL, FK to `lifecycle_policy_version(policy_version_id)` |
| `rule_code` | VARCHAR(64) | NOT NULL |
| `category_code` | VARCHAR(64) | NOT NULL |
| `rule_source` | VARCHAR(24) | NOT NULL; CHECK `RRA_PROPOSED`, `RRA_APPROVED`, `STATUTORY` |
| `duration_value` | SMALLINT | NULL only for externally evaluated schedule rules; otherwise `> 0` |
| `duration_unit` | VARCHAR(8) | NULL only for external schedule; otherwise CHECK `YEAR`, `DAY` |
| `start_basis_code` | VARCHAR(40) | NOT NULL; approved vocabulary, e.g. `ORIGINAL_EVENT`, `LATER_DECISION_CLOSURE`, `IMPLEMENTATION`, `INVESTIGATION_CLOSURE`, `BACKUP_CREATION`, `EXTERNAL_SCHEDULE` |
| `schedule_reference` | VARCHAR(256) | NULL for explicit duration; required for `STATUTORY`/external schedule |
| `created_at` | TIMESTAMPTZ | NOT NULL |

Constraint: UNIQUE `(policy_version_id, rule_code)`; CHECK duration fields are both present and positive or both null with `schedule_reference` non-null. Index `(policy_version_id, category_code)`. Do not populate statutory durations by inference.

| Table / field | Type | Nullability / constraint |
|---|---|---|
| `hold_instruction.hold_id` | UUID | PK |
| `external_instruction_ref` | VARCHAR(128) | NOT NULL |
| `idempotency_key` | VARCHAR(128) | NOT NULL, UNIQUE |
| `case_ref` | VARCHAR(128) | NULL; opaque, no narrative |
| `scope_profile` | VARCHAR(64) | NOT NULL |
| `scope_profile_version` | INTEGER | NOT NULL, `> 0` |
| `instruction_sha256` | CHAR(64) | NOT NULL |
| `requested_by_subject` | VARCHAR(128) | NOT NULL |
| `approved_by_subject` | VARCHAR(128) | NOT NULL, distinct from requester if governance requires |
| `received_at` | TIMESTAMPTZ | NOT NULL |
| `status` | VARCHAR(20) | NOT NULL; CHECK `PENDING_BLOCK`, `ACTIVE`, `RELEASE_PENDING`, `RELEASED`, `REJECTED`, `CONFLICTED` |
| `release_instruction_sha256` | CHAR(64) | NULL until release instruction |
| `released_at` | TIMESTAMPTZ | NULL until all owners acknowledge release |

Index `(status, received_at)` and `(case_ref)` where non-null. Scope definition content stays in an approved schema/catalog and is resolved by source owners; no arbitrary query text is stored/executed.

| Table / field | Type | Nullability / constraint |
|---|---|---|
| `lifecycle_scope_fence.owner_service` | VARCHAR(64) | NOT NULL |
| `scope_key` | VARCHAR(128) | NOT NULL; opaque, owner-resolved scope identifier |
| `current_fence` | BIGINT | NOT NULL, `>= 0`; monotonically incremented transactionally |
| `hold_blocked` | BOOLEAN | NOT NULL |
| `updated_at` | TIMESTAMPTZ | NOT NULL |

PK `(owner_service, scope_key)`; index `(hold_blocked, updated_at)`. Hold registration increments this row and sets `hold_blocked` in one RLC transaction before fan-out. A scope fence is not inferred from a per-hold sequence.

| Table / field | Type | Nullability / constraint |
|---|---|---|
| `hold_owner_ack.hold_id` | UUID | NOT NULL, FK to `hold_instruction(hold_id)` |
| `owner_service` | VARCHAR(64) | NOT NULL |
| `scope_key` | VARCHAR(128) | NOT NULL; owner-resolved affected scope |
| `command_version` | BIGINT | NOT NULL, `> 0` |
| `fence` | BIGINT | NOT NULL, `> 0`; equals the scope fence sent to owner |
| `ack_state` | VARCHAR(20) | NOT NULL; CHECK `PENDING`, `APPLIED`, `RELEASED`, `FAILED`, `CONFLICTED` |
| `ack_at` | TIMESTAMPTZ | NULL until acknowledged |
| `failure_code` | VARCHAR(64) | NULL; no sensitive payload |
| `updated_at` | TIMESTAMPTZ | NOT NULL |

PK `(hold_id, owner_service, scope_key)`; index `(owner_service, scope_key, ack_state, updated_at)`. A new hold generation requires immutable control-event evidence; it must not overwrite earlier acknowledgements without a sequenced event.

| Table / field | Type | Nullability / constraint |
|---|---|---|
| `disposal_batch.batch_id` | UUID | PK |
| `owner_service` | VARCHAR(64) | NOT NULL |
| `scope_key` | VARCHAR(128) | NOT NULL; owner-resolved lifecycle scope |
| `owner_batch_ref` | UUID | NOT NULL, opaque owner-issued snapshot ID |
| `policy_version_id` | UUID | NOT NULL, FK to policy version |
| `record_count` | BIGINT | NOT NULL, `> 0` |
| `record_set_sha256` | CHAR(64) | NOT NULL |
| `hold_fence` | BIGINT | NOT NULL, `>= 0` |
| `archive_evidence_sha256` | CHAR(64) | NULL if archive is not an applicable prerequisite; otherwise required |
| `status` | VARCHAR(20) | NOT NULL; CHECK `PREPARED`, `AUTHORIZED`, `QUEUED`, `EXECUTING`, `PARTIAL`, `COMPLETED`, `CANCELLED`, `REJECTED` |
| `requested_by_subject` | VARCHAR(128) | NOT NULL |
| `created_at` | TIMESTAMPTZ | NOT NULL |
| `updated_at` | TIMESTAMPTZ | NOT NULL |

UNIQUE `(owner_service, owner_batch_ref)`; index `(owner_service, scope_key, status, updated_at)`. A batch covers one owner and one scope/fence; owners split batches that cross scopes. Batch snapshot contents remain with the owner; RLC stores only count and digest.

| Table / field | Type | Nullability / constraint |
|---|---|---|
| `disposal_approval.approval_id` | UUID | PK |
| `batch_id` | UUID | NOT NULL, FK to `disposal_batch(batch_id)` |
| `approval_role` | VARCHAR(32) | NOT NULL; approved vocabulary for Records Management, Legal/Compliance, senior management |
| `decision` | VARCHAR(12) | NOT NULL; CHECK `APPROVE`, `REJECT`, `REVOKE` |
| `approval_sequence` | INTEGER | NOT NULL, `> 0` |
| `approver_subject` | VARCHAR(128) | NOT NULL |
| `evidence_sha256` | CHAR(64) | NOT NULL |
| `decided_at` | TIMESTAMPTZ | NOT NULL |

UNIQUE `(batch_id, approval_role, approval_sequence)`; index `(batch_id, decision)`. Cross-row separation-of-duties checks (approver != executor != verifier) are enforced transactionally in service logic and tested; SQL row CHECK cannot establish identity separation across workflow rows.

| Table / field | Type | Nullability / constraint |
|---|---|---|
| `disposal_execution_claim.claim_id` | UUID | PK |
| `batch_id` | UUID | NOT NULL, FK to `disposal_batch(batch_id)` |
| `owner_service` | VARCHAR(64) | NOT NULL |
| `scope_key` | VARCHAR(128) | NOT NULL |
| `fence` | BIGINT | NOT NULL, `>= 0` |
| `claim_state` | VARCHAR(16) | NOT NULL; CHECK `GRANTED`, `COMPLETED`, `CANCELLED`, `EXPIRED`, `CONFLICTED` |
| `granted_at` | TIMESTAMPTZ | NOT NULL |
| `expires_at` | TIMESTAMPTZ | NOT NULL, `> granted_at` |
| `completed_at` | TIMESTAMPTZ | NULL until terminal completion |
| `owner_execution_ref` | UUID | NULL until owner accepts claim |

UNIQUE `(batch_id)`; index `(owner_service, scope_key, claim_state, expires_at)`. Before creating a claim RLC atomically verifies `hold_blocked = false`, current fence equals the batch fence, all approvals are current, and no active claim exists for the batch. Hold registration first blocks new claims and increments the fence; already granted claims must be cancelled or completed and reconciled before owners acknowledge the hold. Expired claims are not proof that an external action did not occur; owner reconciliation is required before retry.

| Table / field | Type | Nullability / constraint |
|---|---|---|
| `lifecycle_control_event.event_id` | UUID | PK |
| `aggregate_type` | VARCHAR(24) | NOT NULL; CHECK `POLICY`, `HOLD`, `DISPOSAL` |
| `aggregate_id` | UUID | NOT NULL; opaque aggregate key |
| `sequence_no` | BIGINT | NOT NULL, `> 0` |
| `event_type` | VARCHAR(48) | NOT NULL |
| `actor_subject` | VARCHAR(128) | NOT NULL |
| `recorded_at` | TIMESTAMPTZ | NOT NULL |
| `evidence_sha256` | CHAR(64) | NULL when no external evidence applies |
| `metadata` | JSONB | NOT NULL DEFAULT `'{}'::jsonb`; allowlisted, minimized fields only |

UNIQUE `(aggregate_type, aggregate_id, sequence_no)`; index `(aggregate_type, aggregate_id, recorded_at)`. Append-only privileges/triggers are required for this evidence table; no credentials or case narrative belong in metadata.

#### Each source owner's database

The following tables are local to each owner and do not point to another service's database. Concrete FKs use that service's actual record primary key. For Audit, only the lifecycle table may reference `audit_event(id)`; the Audit event row itself is not altered.

| Table / field | Type | Nullability / constraint |
|---|---|---|
| `record_lifecycle.record_ref` | UUID | PK; FK to the same-service record; for Audit, FK to `audit_event(id)` with `ON DELETE RESTRICT` |
| `category_code` | VARCHAR(64) | NULL until classified; unknown never eligible |
| `original_occurred_at` | TIMESTAMPTZ | NULL if source did not supply it; no inferred backfill |
| `ingested_at` | TIMESTAMPTZ | NULL for historical rows where actual ingestion time is unknowable; set once on first accepted ingestion going forward |
| `retention_basis_at` | TIMESTAMPTZ | NULL until all category-specific basis inputs resolve |
| `policy_version_ref` | UUID | NULL until evaluated; opaque cross-service reference, no cross-DB FK |
| `retain_until` | TIMESTAMPTZ | NULL until deterministically calculated |
| `lifecycle_state` | VARCHAR(20) | NOT NULL; CHECK `UNCLASSIFIED`, `ACTIVE`, `ARCHIVED`, `DISPOSAL_PREPARED`, `DISPOSAL_QUEUED`, `DISPOSED`, `EXCEPTION` |
| `updated_at` | TIMESTAMPTZ | NOT NULL |

PK is the source record ID; indexes `(category_code, retain_until, lifecycle_state)` and `(lifecycle_state, updated_at)`. Audit's `record_ref` FK must be restrictive and must not enable deletes; existing append-only triggers remain effective.

| Table / field | Type | Nullability / constraint |
|---|---|---|
| `record_hold_link.hold_id` | UUID | NOT NULL, opaque RLC ID; no cross-DB FK |
| `record_ref` | UUID | NOT NULL, FK to local `record_lifecycle(record_ref)` |
| `hold_fence` | BIGINT | NOT NULL, `> 0` |
| `hold_state` | VARCHAR(16) | NOT NULL; CHECK `PENDING`, `ACTIVE`, `RELEASED`, `CONFLICTED` |
| `applied_at` | TIMESTAMPTZ | NOT NULL |
| `released_at` | TIMESTAMPTZ | NULL until this hold's release is acknowledged |

PK `(hold_id, record_ref)`; index `(record_ref, hold_state, hold_fence)`. A record remains blocked if any link is not `RELEASED`.

| Table / field | Type | Nullability / constraint |
|---|---|---|
| `archive_manifest.manifest_id` | UUID | NOT NULL, logical manifest identifier |
| `policy_version_ref` | UUID | NOT NULL, opaque policy version |
| `manifest_version` | INTEGER | NOT NULL, `> 0` |
| `created_by_subject` | VARCHAR(128) | NOT NULL |
| `created_at` | TIMESTAMPTZ | NOT NULL |
| `record_count` | BIGINT | NOT NULL, `>= 0` |
| `byte_count` | BIGINT | NOT NULL, `>= 0` |
| `digest_algorithm` | VARCHAR(16) | NOT NULL; approved allowlist, e.g. `SHA-256` |
| `manifest_sha256` | CHAR(64) | NOT NULL |
| `signature_key_ref` | VARCHAR(128) | NOT NULL, reference only; private key is external |
| `repository_object_ref` | TEXT | NULL until destination selected/transferred |
| `transfer_state` | VARCHAR(16) | NOT NULL; CHECK `PREPARED`, `TRANSFERRED`, `VERIFIED`, `FAILED`, `QUARANTINED` |
| `verified_by_subject` | VARCHAR(128) | NULL until independent verification |
| `verified_at` | TIMESTAMPTZ | NULL until verified |

PK `(manifest_id, manifest_version)`; index `(transfer_state, created_at)`.

| Table / field | Type | Nullability / constraint |
|---|---|---|
| `archive_manifest_member.manifest_id` | UUID | NOT NULL |
| `manifest_version` | INTEGER | NOT NULL |
| `record_ref` | UUID | NOT NULL, FK to local `record_lifecycle(record_ref)` |
| `record_sha256` | CHAR(64) | NOT NULL |

PK `(manifest_id, manifest_version, record_ref)`; FK `(manifest_id, manifest_version)` to the local `archive_manifest`; index `(record_ref)`. The signed manifest payload and indexed members must reconcile exactly; payload is not duplicated into RLC.

| Table / field | Type | Nullability / constraint |
|---|---|---|
| `disposal_item.batch_id` | UUID | NOT NULL, opaque RLC batch ID; no cross-DB FK |
| `record_ref` | UUID | NOT NULL, FK to local `record_lifecycle(record_ref)` |
| `item_state` | VARCHAR(16) | NOT NULL; CHECK `PREPARED`, `QUEUED`, `EXECUTING`, `DISPOSED`, `CANCELLED`, `FAILED` |
| `hold_fence` | BIGINT | NOT NULL, `>= 0` |
| `execution_idempotency_key` | VARCHAR(128) | NOT NULL |
| `executed_at` | TIMESTAMPTZ | NULL until successful owner operation |
| `result_code` | VARCHAR(48) | NULL until attempted |
| `evidence_sha256` | CHAR(64) | NULL until outcome evidence exists |

PK `(batch_id, record_ref)`; UNIQUE `(execution_idempotency_key)`; index `(item_state, hold_fence)`. State changes are append-recorded in the following owner-local evidence table; repeated commands return the same outcome, and stale fences cannot advance state.

| Table / field | Type | Nullability / constraint |
|---|---|---|
| `record_lifecycle_event.event_id` | UUID | PK |
| `record_ref` | UUID | NOT NULL, FK to local `record_lifecycle(record_ref)` |
| `sequence_no` | BIGINT | NOT NULL, `> 0` |
| `event_type` | VARCHAR(48) | NOT NULL |
| `actor_subject` | VARCHAR(128) | NOT NULL |
| `recorded_at` | TIMESTAMPTZ | NOT NULL |
| `evidence_sha256` | CHAR(64) | NULL when no external evidence applies |
| `metadata` | JSONB | NOT NULL DEFAULT `'{}'::jsonb`; allowlisted, minimized values only |

UNIQUE `(record_ref, sequence_no)`; index `(record_ref, recorded_at)`. Runtime roles receive append-only insert/select permissions only; mutation/deletion protection must be approved and tested for each owner.

The existing audit table's append-only protections mean even these proposed lifecycle tables cannot authorize deleting an Audit row. Any proposed local `archive_manifest_member` table or owner-specific case-selector storage must be specified against the actual source schema before implementation; this conceptual register does not silently establish those service-specific fields.

### H.8 Proposed API/event responsibilities and security

Interfaces below are internal proposals, not frozen or approved contracts. No API Gateway route is proposed.

| Interface | Caller / owner | Responsibility and rules |
|---|---|---|
| Register/release hold instruction | Authorized RRA actor to RLC | Accept only written-instruction evidence reference/digest, bounded scope profile/version, opaque case reference, idempotency key and authenticated actor. Approval and release permissions are distinct policy roles. |
| Apply/release hold command | RLC to each source owner's internal adapter | Idempotent, sequenced command with hold ID and fence; owner resolves its own scope, applies local blockers and returns an acknowledgement/status/evidence digest. A notification event may retry delivery but cannot substitute for acknowledgement. |
| Prepare disposal batch | Source owner to RLC | Submit owner batch reference, policy version, record count, snapshot digest, hold fence and archive evidence reference. Owner excludes unknown/ineligible records and keeps record-level snapshot locally. |
| Approve/cancel disposal | Authorized approver to RLC | Bind every approval to exact batch digest and approval sequence; changed snapshot, policy, hold fence or archive proof invalidates approval. Reject/revoke is idempotent and recorded. |
| Execute disposal | RLC authorization to the owner, then owner-local execution | Single-use batch/fence claim; source rechecks holds, policy, approvals and digest immediately before its irreversible step. No central generic delete API, no cross-service DB access, no API Gateway exposure. |
| Publish lifecycle evidence | Owner to RLC | Submit state/outcome, counts, digests, certificate reference and independent verification reference; detailed source record data stays with owner. |

All interfaces require authenticated service/user principals, least-privilege scopes, TLS 1.2+, replay protection/idempotency, monotonic version/fence checks, explicit audit evidence and fail-closed authorization. Privileged users require MFA. Secrets/private signing keys remain in an approved key-management facility, not schemas or logs. Requests/errors/metrics must not include record bodies, personal data or credentials. Availability loss, timeout, malformed response or stale acknowledgement means no disposal and no confirmed hold release.

RLC command delivery should use a transactional outbox and retry/reconciliation. Consumers deduplicate by command ID and enforce sequence/fence; poison/conflicting commands go to an explicitly monitored dead-letter workflow and retain the disposal block. Do not introduce a broker dependency without selecting and approving the operational design.

### H.9 Failure, recovery and operational evidence

- Hold apply/release is retried and reconciled by owner and fence. An unavailable owner leaves the instruction pending and blocks affected disposal. No “best effort” success.
- Disposal is fail-closed on RLC unavailability, owner mismatch, missing/expired approval, stale policy/fence, archive mismatch, unknown category/start, incomplete hold acknowledgements, ambiguous time basis, or duplicate/inconsistent execution result.
- Archive transfer uses idempotent object keys, immutable manifest versions, durable transfer state, quarantine on digest mismatch, and reconciliation before retry. Restore is verified independently; a failed restore is an incident, not a successful archive test.
- Partial destruction is represented explicitly per item; never retry a non-idempotent physical action blindly. Reconcile source inventory, archive, replicas and backup state before resuming; require independent review.
- Monitoring reports counts/status/latencies/error codes only. No event body, case narrative, PII, secret, token, or record payload in metrics/logs/health output. Define alert ownership and operational thresholds only after RRA/technical-owner approval; do not invent SLOs.

### H.10 Implementation sequence and acceptance tests

Implementation remains blocked pending policy and owner decisions. If later authorized, sequence is:

1. Close GOV-01–04 and assign all category source owners and technical owners; approve retention semantics, effective-date/retroactivity and external schedule evaluation.
2. Approve RLC architecture, RACI, identity roles, service availability/security, scope profiles, hold fence protocol, and explicit handling of in-flight destructive operations.
3. Approve exact per-service schema/API/event contracts and data-minimization review; preserve existing Search API/event contracts unless separately approved.
4. Implement source classification and timestamp evidence first; prove completeness and idempotency before any retention calculator.
5. Implement policy evaluation in non-destructive/report-only mode and compare decisions to independently reviewed examples; do not enable deletion.
6. Implement hold propagation and owner acknowledgements; prove fail-closed concurrency before any disposal workflow.
7. Approve repository/provider, keys, manifest format, independent verification, restore and recovery evidence; implement archive and reconcile.
8. Implement approvals and owner-side disposal only after separate authorization; begin with non-destructive dry-run, then controlled test records, independent verification and complete regression.

Required focused test additions (all currently absent) include:

- Calendar vs fixed-day arithmetic, leap-day, UTC/time-zone normalization, exact-before/equal/after expiry, policy effective boundaries, version changes and explicit retroactivity.
- Original event time vs first ingestion, duplicate/out-of-order delivery, producer clock skew, missing timestamp, transaction not closed, and no inferred historical ingestion timestamp.
- Multiple category/statutory requirements choose the maximum; unresolved statutory schedule, unknown category and missing source owner are ineligible.
- Individual and case holds, bounded selection membership, continuing vs snapshot scope, concurrent holds, release of one hold only, overlapping holds, stale sequence, duplicate/reordered command and owner outage.
- Race tests interleaving hold registration with prepared/queued/claimed disposal; assert queued work is cancelled, stale fences rejected, no new irreversible execution starts, in-flight work is escalated, and RLC never reports ACTIVE before required owner acknowledgement.
- Authorization/identity tests for written evidence, separation of requester/approver/executor/verifier, MFA/least privilege, revocation, unauthorized cross-service calls and no Gateway route.
- Archive manifest determinism, source signature, encryption configuration, count/byte/digest reconciliation, corruption/tamper detection, duplicate transfer, interrupted transfer, quarantine, restore integrity and independent verification.
- Disposal batch snapshot binding, changed-hold/policy invalidation, idempotent retries, partial failure, certificate reconciliation, exclusion of held/unknown records, no deletion from Audit under current protections, and complete owner/control evidence.
- Privacy tests prove payloads, AI input, case narrative, credentials and tokens are absent from logs, metrics, errors and operational endpoints.
- Full regression for all affected owner services, Audit, Identity, common-web, PostgreSQL privilege/trigger behavior and messaging; report actual counts, failures, errors and skips.

### H.11 Remaining decisions and implementation gate

| ID | Outstanding decision / dependency | Why it remains open | Evidence to close | Status |
|---|---|---|---|---|
| DES-01 | Approve RLC service ownership/placement, availability, identity model and RRA-to-service responsibility matrix. | No lifecycle control-plane exists; roles and operational owners are unassigned. | Architecture decision plus named accountable owners, approved security/deployment design and operational support model. | BLOCKED |
| DES-02 | Approve calendar/fixed-day semantics, exact boundary, leap-day behavior, time-zone/clock source, policy selection and retroactivity. | Technical defaults in H.3 are recommendations, not legal meanings. | Written RRA Legal/Records Management decision and worked approved boundary examples. | BLOCKED |
| DES-03 | Assign authoritative sources, category classifiers, timestamps and lifecycle adapters for all categories. | AI, incident, financial/tax, legal/regulatory and production backups have no verified RoomIQ source/owner. | Per-category signed source/owner matrix, exact event/entity and completeness evidence. | BLOCKED |
| DES-04 | Approve bounded hold scope profiles, future-record semantics, effective-time rule, owner acknowledgement and in-flight disposal race treatment. | No hold model exists; distributed operations cannot reverse an already completed destruction. | Written authority/scope protocol, concurrency design and signed acceptance of in-flight exception behavior. | BLOCKED |
| DES-05 | Approve exact schema, indexes, state transitions, event evidence and per-service APIs. | H.7/H.8 are proposals and affect multiple service-owned databases/contracts. | Explicit approval of exact table/field/type/nullability/constraints/indexes and interface payloads by each owner and RRA. | BLOCKED |
| DES-06 | Resolve Audit append-only versus eventual disposal. | Current triggers reject delete/truncate; no approved lifecycle mechanism can dispose `audit_event`. | Separate reviewed proposal and explicit approval preserving evidence and defining allowed disposal mechanics. | BLOCKED |
| DES-07 | Approve repository, encryption/key ownership, manifest verifier, backup/recovery and access evidence. | No provider, production controls, key owner, restore process or recovery evidence is identified. | Approved infrastructure/security design and deployment/restore evidence. | BLOCKED |
| DES-08 | Approve disposal authority matrix, destruction method, certificate, independent verifier and evidence-retention category. | Roles, method and disposal evidence lifecycle are absent. | Written RRA governance plus tested separation-of-duties and certificate/reconciliation criteria. | BLOCKED |

**Gate:** Design refinement is recorded; implementation readiness is not achieved. S8-06 remains **BLOCKED** pending formal policy approval and closure of the above dependencies. S8-07 remains **PARTIALLY VERIFIED / BLOCKED**. Stage 8 acceptance is not claimed.
