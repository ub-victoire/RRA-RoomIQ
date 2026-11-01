# RRA RoomIQ Backend Documentation

## Implementation roadmap

### Current Stage 7 status

S7-01 and S7-02 are complete at the source-code and repository-verification level. S7-02's focused Notification routing/transaction selection passed 20 tests; its complete Notification suite passed 39, Identity passed 62, common-web passed 6, Booking's focused event-routing suite passed 5, and the final 10-module backend reactor passed all 379 tests with zero failures, errors, or skips. Booking request events carry v2 routing facts; Notification resolves scoped active Admins through Identity, routes decisions to the persisted requester, and uses service-owned identifiers for cancellation and Room conflict/maintenance notices. Organizer-only reminders remain configured; completion/release/no-show remain receipt-only pending separate recipient-policy approval. Correlation is propagated to Identity and event receipts retain causation. No ERD or migration change was made, and no Notification Gateway route was added. Production publisher credentials and routing-key ACLs remain unverified deployment settings. S7-03 through S7-08 remain proposed. The authoritative status is tracked in [Backend Documentation](../Documentation.md#stage-7-requirement-checklist-approved) and the [Stage 7 event contract](../api-contracts/rra-roomiq-stage1-contract.json).

The authoritative end-to-end roadmap, requirement checklists, and stage-gate rules are maintained in [Backend Documentation](../Documentation.md#end-to-end-implementation-roadmap). Stages 3 through 5 are accepted; Stage 6 was accepted by the user on 2026-10-12 after S6-01 through S6-14 completed verification. Booking's protected workflows, owner-service authorization, recurring occupancy, meeting visibility, cancellation/extension, share links, and transactional outbox are documented in [Booking Service](../booking-service/README.md) and the API contract. The current Stage 7 implementation and gate status is recorded above and in the central roadmap.

## Stage 1 status

Stage 1 and Stage 2 foundation details and the accepted Stages 3 through 6 remain documented in their service guides and the central roadmap. Scheduling owns calendar rules and availability; Room retains room status and maintenance; Booking owns confirmed occupancy and its overlap barrier. Scheduling's protected APIs use Identity authorization and Booking's fail-closed occupancy provider. The previous S6-14 regression gate passed 311 tests; the final S7-02 regression totals are listed under Current Stage 7 status above.

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
