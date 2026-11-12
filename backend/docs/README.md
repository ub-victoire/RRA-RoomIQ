# RRA RoomIQ Backend Documentation

## Implementation roadmap

### Current Stage 7 status

Stage 7 was accepted by the user after S7-01 through S7-08 passed their requirement verification gates. S7-06 adds durable EMAIL notification rows, trusted active-account address resolution through a dedicated Identity service permission, asynchronous delivery through the vendor-neutral SMTP adapter, and PostgreSQL delivery-attempt persistence. S7-07 adds five-attempt bounded retries, durable scheduled retry state, permanent/exhausted dead letters, sanitized history, protected idempotent redrive, aggregate delivery health, and a dedicated Identity permission. S7-08 verifies PostgreSQL/RabbitMQ integration, retry/dead-letter behavior, and the Secretary request → scoped Admin → requester decision notification flow, followed by Identity/common-web and full-reactor regressions. Production sending remains disabled until RRA provides the approved relay and organization-controlled sender configuration; SMTP exactly-once delivery is not claimed. S7-05's strict renderer requires an active template whose code equals the event type; only safe allowlisted facts render, and missing/malformed templates fail processing. Template administration requires an active global Super Admin with `NOTIFICATION_TEMPLATE_MANAGE`; changes and safe audit metadata are persisted transactionally and published through the approved outbox to Audit Service. S7-02's completion/release/no-show receipt-only policies and production RabbitMQ credential/routing-key ACL deployment tasks remain as documented below. No Notification business route was added to the API Gateway. The authoritative status and test commands are tracked in [Backend Documentation](../Documentation.md#stage-7-requirement-checklist-approved) and the [Stage 7 event contract](../api-contracts/rra-roomiq-stage1-contract.json).

The authoritative end-to-end roadmap, requirement checklists, and stage-gate rules are maintained in [Backend Documentation](../Documentation.md#end-to-end-implementation-roadmap). Stages 3 through 5 are accepted; Stage 6 was accepted by the user on 2026-10-12 after S6-01 through S6-14 completed verification. Stage 7 is accepted. Stage 8's checklist is approved; S8-01 and S8-02 are accepted for their documented catalog and producer scope; S8-03 is implemented and verified subject to production DBA provisioning; S8-04 is implemented and verified for supported event contracts; and S8-05 is complete and full-reactor verified. Audit consumes Identity v1 audit-outbox and Notification template-audit events, applies append-only database protections, and exposes the frozen authorized Search API. SRS action coverage remains partial/missing as documented in the [action-owner matrix](audit-action-owner-matrix.md); there is no live multi-service E2E evidence. The user-approved search policy requires global Super Admin or assigned-building Admin access with dedicated `AUDIT_READ`.

The [S8-06 Implementation Readiness Register](s8-06-implementation-readiness-register.md) remains the authoritative source for retention/lifecycle decisions and evidence. RLC implements policy-version/rule registry and lifecycle APIs, transactional lifecycle-event evidence, database guards, role separation, and non-destructive eligibility calculation. The calculator is not disposal authorization and is not connected to trusted source-owner evidence or cross-category classification. The register records 36 open S8-06A decisions and blank approval fields. Documentation cites a CTO S8-06C implementation-baseline instruction, but no signed instruction is present in the repository and it does not substitute for formal Legal/Records Management validation or policy approval. Source-owner adapters, operational holds, archive, disposal, and production provisioning remain outstanding. HOLD-01 through HOLD-05 remain unresolved; S8-06D and S8-06F have no authoritative definitions; ARC-08 and DSP-06 are missing matching approval-package rows. S8-06 remains blocked; S8-07 and Stage 8 acceptance remain blocked.

The project-level S8-06B provisional decisions are recorded in [s8-06b-provisional-decisions.md](s8-06b-provisional-decisions.md). Those decisions are explicitly `PROVISIONALLY ADOPTED` by the `Project CTO / Project Lead`, marked `PENDING RRA REVIEW — NOT YET VALIDATED`, and intentionally do not claim RRA approval, legal validation, or production readiness.

## Stage 1 status

Stage 1 and Stage 2 foundation details and the accepted Stages 3 through 6 remain documented in their service guides and the central roadmap. Scheduling owns calendar rules and availability; Room retains room status and maintenance; Booking owns confirmed occupancy and its overlap barrier. Scheduling's protected APIs use Identity authorization and Booking's fail-closed occupancy provider. The previous S6-14 regression gate passed 311 tests; final S7-08 commands and results are recorded in the [runtime validation report](runtime-validation-report.md).

### SRS/ERD traceability

Identity implementation alignment with the authoritative Backend SRS, Final SRS, and ERD is recorded in [identity-srs-erd-alignment.md](identity-srs-erd-alignment.md). Organization schema alignment is recorded in [organization-srs-erd-alignment.md](organization-srs-erd-alignment.md). The organization change adds the explicitly approved `FLOOR.created_at` field; the identity audit outbox remains service-local infrastructure that publishes to the Audit Service.

### Current architecture baseline

- API Gateway
- Identity/Auth Service
- Organization/Location Service
- Room Management Service
- Scheduling Service
- Booking/Meeting Service
- Notification Service
- Audit Service
- Records Lifecycle Control Service
- Analytics Service

The `common-web` module is consumed by every service and provides the shared response, error, validation, OpenAPI, correlation-ID, exception-handling, and logging conventions.

Organization Service owns its S3-01 schema, S3-02 persistence/domain model, S3-03 hierarchy integrity, S3-04 APIs, S3-05 validation/lifecycle policy, S3-06 authorization, and S3-07 API contract/OpenAPI completeness. S3-08 verified that Identity stores Organization references as identifiers only; S3-09 verified isolated Organization test coverage; S3-10 synchronized docs, contracts, and configuration notes; S3-11 clean service/Identity/shared/full-reactor verification passed. Stage 3 is accepted. Room Service S4-01 through S4-12 are implemented, documented, verified, and accepted. Scheduling S5-01 through S5-07 are implemented and verified; all `/api/v1/**` business routes require bearer/Identity authorization and are not routed through the Gateway pending Stage 11. The service accepts no caller-supplied identity or scope. Recurrence uses the existing ERD table with one-year/365-occurrence bounds, S5-05 validates candidate occurrences, and S5-06 provides owner-API-based continuous availability candidates with fail-closed Booking occupancy integration. Booking confirmation and occupancy ownership remain in S6.

S3-05 enforces nonblank/length and database uniqueness constraints, timezone path format plus service-validated IANA IDs with the `Africa/Kigali` default, active parent/child rules, recursive active-descendant protection, department subtree scope safety, and explicit nullable PUT semantics. Flyway V3/V4 add database checks; no ERD change was required. Organization/common-web passed 7 suites/20 tests and identity/common-web passed 12/37, with zero failures/errors/skips.

### Shared architectural rules

- Each service owns its own persistence boundary.
- No cross-service database access or JPA entity sharing.
- Public APIs are versioned under `/api/v1`.
- Business logic remains in the service layer.
- Authentication and authorization remain server-side authoritative.
- All schema changes must be reflected in migration scripts.

### Infrastructure baseline

The backend includes a shared local infrastructure plan for:

- PostgreSQL
- RabbitMQ
- Redis

The `backend/infrastructure/docker-compose.yml` file is the local development starting point.

### Environment handling

Use the project-level `.env.example` template as the source for local environment values. Sensitive values must not be committed to the repository.

### Verification

Verified with:

```bash
cd "/home/victoire/Desktop/My_Projects/RRA_Projects/RRA_RoomIQ"
mvn -q -f backend/pom.xml clean test
```

This verifies the complete backend reactor, including shared standards and identity integration tests.

### Implementation changelog

### 2026-09-30
- Completed approved S3-01 organization schema migration and synchronized the ERD, roadmap, and stage contract.
- Completed S3-02 organization persistence/domain model and focused H2 round-trip coverage; no additional ERD change was required. Focused organization tests (3 suites/4 tests), identity/common-web (12/37), and full reactor (22/48) passed; build succeeded.
- Completed S3-03 hierarchy foreign-key and cycle/scope integrity; focused organization tests (3 suites/5 tests), identity/common-web (12/37), and full reactor (22/49) passed; build succeeded.
- Completed S3-04 CRUD/status/search/filter/page/sort and hierarchy APIs; focused Organization tests (4 suites/6 tests), identity/common-web (12/37), and full reactor (23/50) passed; build succeeded.
- Completed S2-14 through S2-17: stable identity errors/audit, automated identity coverage, executable Postman contract/sample data, and SRS/ERD/documentation alignment.
- Completed S2-17 SRS/ERD/documentation synchronization and added the identity alignment note.
- Completed S2-18 identity and full-reactor verification with zero failures/errors.
- Defined the Stage 3 Organization and Locations requirement checklist and implementation constraints.
- Synchronized the backend roadmap, identity documentation, API contract, collection validator, and shared-module notes.
- Verified the clean backend reactor: 20 suites, 45 tests, zero failures/errors.

### 2026-09-28
- Completed and verified Stage 1 backend foundation setup.
- Added shared backend parent Maven project and runnable service modules.
- Added the common-web shared standards module.
- Added service application entry points, configuration, Actuator, and smoke tests.
- Created environment placeholder configuration and infrastructure baseline.
- Synced backend documentation with the actual implementation state.
- Verified the complete backend reactor passes its Maven test lifecycle.

#### 2026-09-27
- Initialized the Spring Boot identity service foundation.
- Fixed datasource and environment configuration.
- Established Flyway, JPA, security, actuator, and pagination baseline.
- Confirmed service startup and test execution.
