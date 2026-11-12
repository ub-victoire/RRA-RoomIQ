# S8-06B Provisional Project Decisions and Unblocking Plan

## Status and decision boundary

This document records the project-level decisions used to unblock planning and implementation work for S8-06 and S8-07 while formal RRA review remains pending. All decisions in this document are:

- `PROVISIONALLY ADOPTED`
- owned by `Project CTO / Project Lead`
- marked `PENDING RRA REVIEW — NOT YET VALIDATED`
- explicitly not a substitute for RRA Legal, Records Management, Security, Operations, or source-owner approval

The project-level decision package is intended to preserve technical momentum without claiming any legal, records-management, or organizational approval that has not been given. The project adopts configurable, fail-closed defaults where legal or organizational validation is still required, and it records the unresolved decision boundaries separately from the implementation roadmap.

## Decision principles

1. Implementation may continue only with project-level decisions that are safe by default and non-destructive.
2. Retention, archive, legal hold, distribution, and disposal controls remain implementation planning defaults until RRA review validates them.
3. No decision in this document authorizes destruction, transfer, or legal-hold enforcement without formal approval.
4. Source-owner evidence remains authoritative; RoomIQ shall treat missing or conflicting evidence as non-eligible and require manual resolution.
5. The project shall not infer statutory retention periods, legal citations, or organization approvals that are not present in the repository.
6. The project shall not claim Stage 8 completion, stage acceptance, or live multi-service E2E readiness based on these provisional decisions alone.

## Original 36 decision inventory preserved

The original decision record inventory remains intact. The following IDs continue to represent the underlying RRA decision set and are not replaced or renumbered.

| ID | Decision meaning | Project provisional status |
|---|---|---|
| S8-06A-POL-01 | Validate proposed periods and categories against applicable legislation and RRA schedules | `PROVISIONALLY ADOPTED` ; `PENDING RRA REVIEW — NOT YET VALIDATED` |
| S8-06A-POL-02 | Formal management approval, approval reference, version, effective date, and responsible authority | `PROVISIONALLY ADOPTED` ; `PENDING RRA REVIEW — NOT YET VALIDATED` |
| S8-06A-POL-03 | Confirm scope and definitions for category mapping and calculation | `PROVISIONALLY ADOPTED` ; `PENDING RRA REVIEW — NOT YET VALIDATED` |
| S8-06A-POL-04 | Define authority for statutes, RRA schedules, and conflict resolution | `PROVISIONALLY ADOPTED` ; `PENDING RRA REVIEW — NOT YET VALIDATED` |
| S8-06A-TIM-01 | Define retention start events and authoritative timestamps | `PROVISIONALLY ADOPTED` ; `PENDING RRA REVIEW — NOT YET VALIDATED` |
| S8-06A-TIM-02 | Define time-zone and boundary semantics for retention calculations | `PROVISIONALLY ADOPTED` ; `PENDING RRA REVIEW — NOT YET VALIDATED` |
| S8-06A-TIM-03 | Define handling of missing, conflicting, or incomplete evidence | `PROVISIONALLY ADOPTED` ; `PENDING RRA REVIEW — NOT YET VALIDATED` |
| S8-06A-OWN-01 | Assign authoritative source-system ownership and evidence responsibility | `PROVISIONALLY ADOPTED` ; `PENDING RRA REVIEW — NOT YET VALIDATED` |
| S8-06A-OWN-02 | Define source-owner acknowledgment and failure handling | `PROVISIONALLY ADOPTED` ; `PENDING RRA REVIEW — NOT YET VALIDATED` |
| S8-06A-AI-01 | Define AI-record retention and lifecycle treatment | `PROVISIONALLY ADOPTED` ; `PENDING RRA REVIEW — NOT YET VALIDATED` |
| S8-06A-ARC-01 | Approve archive destination and ownership | `PROVISIONALLY ADOPTED` ; `PENDING RRA REVIEW — NOT YET VALIDATED` |
| S8-06A-ARC-02 | Approve record transfer and source/archive reconciliation rules | `PROVISIONALLY ADOPTED` ; `PENDING RRA REVIEW — NOT YET VALIDATED` |
| S8-06A-ARC-03 | Define archive format and metadata requirements | `PROVISIONALLY ADOPTED` ; `PENDING RRA REVIEW — NOT YET VALIDATED` |
| S8-06A-ARC-04 | Define encryption and key ownership | `PROVISIONALLY ADOPTED` ; `PENDING RRA REVIEW — NOT YET VALIDATED` |
| S8-06A-ARC-05 | Define integrity verification and tamper evidence | `PROVISIONALLY ADOPTED` ; `PENDING RRA REVIEW — NOT YET VALIDATED` |
| S8-06A-ARC-06 | Define recovery and restoration requirements | `PROVISIONALLY ADOPTED` ; `PENDING RRA REVIEW — NOT YET VALIDATED` |
| S8-06A-ARC-07 | Define backup, archive preservation, and evidence retention | `PROVISIONALLY ADOPTED` ; `PENDING RRA REVIEW — NOT YET VALIDATED` |
| S8-06A-HOLD-01 | Define legal-hold authority, evidence, and who may initiate or release a hold | `PROVISIONALLY ADOPTED` ; `PENDING RRA REVIEW — NOT YET VALIDATED` |
| S8-06A-HOLD-02 | Define hold scope and record targeting | `PROVISIONALLY ADOPTED` ; `PENDING RRA REVIEW — NOT YET VALIDATED` |
| S8-06A-HOLD-03 | Define hold registration and enforcement behavior | `PROVISIONALLY ADOPTED` ; `PENDING RRA REVIEW — NOT YET VALIDATED` |
| S8-06A-HOLD-04 | Define hold release and revocation semantics | `PROVISIONALLY ADOPTED` ; `PENDING RRA REVIEW — NOT YET VALIDATED` |
| S8-06A-HOLD-05 | Define management of overlapping and conflicting holds | `PROVISIONALLY ADOPTED` ; `PENDING RRA REVIEW — NOT YET VALIDATED` |
| S8-06A-DSP-01 | Approve exact eligibility criteria for disposal | `PROVISIONALLY ADOPTED` ; `PENDING RRA REVIEW — NOT YET VALIDATED` |
| S8-06A-DSP-02 | Define approval and authorization chain for disposal | `PROVISIONALLY ADOPTED` ; `PENDING RRA REVIEW — NOT YET VALIDATED` |
| S8-06A-DSP-03 | Define execution process and evidence preservation | `PROVISIONALLY ADOPTED` ; `PENDING RRA REVIEW — NOT YET VALIDATED` |
| S8-06A-DSP-04 | Define disposal certificate and reconciliation requirements | `PROVISIONALLY ADOPTED` ; `PENDING RRA REVIEW — NOT YET VALIDATED` |
| S8-06A-DSP-05 | Define stewardship and independent review for disposal evidence | `PROVISIONALLY ADOPTED` ; `PENDING RRA REVIEW — NOT YET VALIDATED` |
| S8-06A-AUD-01 | Define Audit retention and evidence controls | `PROVISIONALLY ADOPTED` ; `PENDING RRA REVIEW — NOT YET VALIDATED` |
| S8-06A-DB-01 | Define database role, ownership, and operational provisioning requirements | `PROVISIONALLY ADOPTED` ; `PENDING RRA REVIEW — NOT YET VALIDATED` |
| S8-06A-TECH-01 | Confirm exact sequence allocation and locking semantics | `PROVISIONALLY ADOPTED` ; `PENDING RRA REVIEW — NOT YET VALIDATED` |
| S8-06A-TECH-02 | Decide per-service source evidence adapters and transport semantics | `PROVISIONALLY ADOPTED` ; `PENDING RRA REVIEW — NOT YET VALIDATED` |
| S8-06A-TECH-03 | Define integration patterns and fallback behavior for hold/disposal | `PROVISIONALLY ADOPTED` ; `PENDING RRA REVIEW — NOT YET VALIDATED` |
| S8-06A-OPS-01 | Define operational runbook, monitoring, and credential delivery | `PROVISIONALLY ADOPTED` ; `PENDING RRA REVIEW — NOT YET VALIDATED` |
| S8-06A-OPS-02 | Define operational deployment and verification responsibilities | `PROVISIONALLY ADOPTED` ; `PENDING RRA REVIEW — NOT YET VALIDATED` |
| S8-06A-SEC-01 | Define security, privacy, and access boundaries | `PROVISIONALLY ADOPTED` ; `PENDING RRA REVIEW — NOT YET VALIDATED` |
| S8-06A-TEST-01 | Define testing, evidence, and acceptance criteria for retention, archive, hold and disposal | `PROVISIONALLY ADOPTED` ; `PENDING RRA REVIEW — NOT YET VALIDATED` |

## Project-level provisional decisions for S8-06B

### 1. Retention lifecycle policy defaults

The project adopts a fail-closed lifecycle policy default for implementation planning while RRA review remains pending:

- Unknown categories remain non-eligible.
- Missing or conflicting authoritative start timestamps remain non-eligible.
- Only an approved policy version may be used to compute expiry.
- Legal hold or unresolved ownership state blocks disposal decisions.
- Policy evaluation remains read-only and non-destructive.
- No automatic archive or disposal execution is permitted from this provisional decision package.

### 2. Retention timing and calculation defaults

The project adopts the following implementation-safe defaults for design and test work, explicitly labeled as provisional and not legal policy:

- Retention start is the source owner's authoritative event time, not the Audit ingestion timestamp.
- Time zone handling uses UTC for the normalized comparison and calculation path.
- Calendar-year rules use the approved calendar semantics defined in implementation code and tests; exact legal interpretations remain pending RRA review.
- Missing or conflicting evidence routes the record to manual resolution rather than automatic disposal.
- Evaluation is non-destructive and uses fail-closed behavior.

### 3. Ownership and evidence defaults

The project adopts the following ownership model for planning and implementation:

- Source services remain the owner of their authoritative record facts.
- Audit stores persisted copies only and does not become the source of truth for legal records classification.
- Identity remains authoritative for active session and authorization state.
- The project CTO / Project Lead is the implementation owner for provisional project decisions pending RRA review.
- Source-owner acknowledgement remains required before any operational archive or disposal workflow is enabled.

### 4. AI records and source evidence

The project adopts a provisional default for AI-related retention decisions:

- AI decisions and outputs are treated as record-bearing artifacts only when the source owner explicitly classifies them as records.
- The retention start may use the later of decision time and transaction closure only when a designated source owner documents that rule and approved policy is in place.
- Until formal RRA validation occurs, AI-record retention remains a project-level risk item and not a validated legal classification.

### 5. Archive and preservation defaults

The project adopts a provisional archive design only for implementation readiness planning, not operational approval:

- Archive destination, format, key management, transfer verification, and restore readiness remain configurable requirements.
- The system shall support evidence preservation and verification hooks but not execute archive transfer without formal approval.
- Production archive paths and ownership remain blocked behind the RRA-approved archive decision package.

### 6. Legal-hold defaults

The project records the following provisional hold design defaults for implementation planning only:

- Hold status must be explicit and cannot be inferred merely from a record's age.
- A valid hold blocks disposal while active.
- Release requires an authorized, recorded decision and a verification step.
- Overlapping or conflicting holds remain manual escalation items until the formal RRA ownership and authority model is approved.

### 7. Disposal defaults

The project adopts the following disposal-default rules for planning and acceptance criteria only:

- Eligibility is a technical decision aid and not authorization to dispose.
- Disposal authorization remains separate from retention eligibility.
- The project shall preserve disposal evidence and reconciliation records without implying that the business decision has been approved.
- Disposal workflow execution remains pending final RRA and source-owner approval.

### 8. Audit and database defaults

The project adopts the following operational defaults for design and testing:

- Audit remains append-only in the documented database controls.
- Database principals, migration ownership, and operational provisioning remain project prerequisites, not RRA approvals.
- The project shall not assume production database superuser or owner bypass privileges are acceptable for governance controls.

### 9. Technical, operational and security defaults

The project adopts the following provisional defaults for the implementation backlog:

- Sequence allocation, locking semantics, and hold/disposal race prevention remain technical safeguards to be validated in implementation.
- Operational runbooks, credential handling, and monitoring remain prerequisite work items.
- Security and privacy controls remain fail-closed and explicit; no sensitive payload or secret is accepted as a design requirement without review.

## Explicit unresolved boundary items

The following items must remain unresolved in the authoritative project documentation and must not be marked as approved or complete:

- `ARC-08` criteria exist without a matching S8-06A-ARC-08 decision row; this is recorded as an explicit traceability gap requiring RRA/project-owner clarification.
- `DSP-06` criteria exist without a matching S8-06A-DSP-06 decision row; this is recorded as an explicit traceability gap requiring RRA/project-owner clarification.
- `S8-06D` remains undefined in the repository and remains an unresolved definition/ownership gap.
- `S8-06E-03` is retained as an explicitly unapproved source-evidence contract proposal, not an approved interface.
- `S8-06F` remains undefined and requires the authoritative checklist owner to specify the requirement and approval pathway.
- `HOLD-01` through `HOLD-05` remain unresolved and are not treated as legal-hold operationally approved.

## Work that is unblocked by this provisional package

The following work is considered implementable at the project level without implying RRA approval:

- retention policy registry and lifecycle event handling that is fail-closed and auditable
- non-destructive eligibility logic using explicit inputs and manual escalation on uncertainty
- policy validation and versioning workflows with append-only lifecycle evidence
- database role separation and migration/operational design proof points
- unit and PostgreSQL/Testcontainers validation for the implemented policy and lifecycle controls

## Work that remains blocked

The following work remains blocked until the required RRA decisions or source-owner approvals are supplied:

- approved retention schedule and policy validation
- archive destination, transfer, and restore controls
- legal-hold operational workflow and release authority
- disposal authorization, execution, certificates, and reconciliation
- operational Source-owner evidence adapters and contract validation
- production provisioning, DBA role setup, credentials, and broker/host access controls
- final S8-06 and S8-07 completion acceptance

## Acceptance wording for project-level use

This package is to be used by project leadership for technical planning only. It is not legal advice, formal RRA retention-policy approval, or evidence of operational readiness. Any implementation that depends on these decisions must still satisfy the final RRA review and source-owner requirements before it is treated as production-approved.
