# Runtime Validation Report

**Generated**: 2026-11-02T11:42:29+02:00  
**Target**: `/home/victoire/Desktop/My_Projects/RRA_Projects/RRA_RoomIQ/backend`

## Summary

| Step | Status | Exit Code | Details |
|---|---|---:|---|
| Startup | PASS | 0 | Spring Boot integration-test contexts, PostgreSQL, and RabbitMQ started under Testcontainers; the Notification listener consumed messages |
| Focused integration | PASS | 0 | RabbitMQ/PostgreSQL Secretary request → scoped Admin → requester approval flow; 6 tests, no failures/errors/skips |
| Notification regression | PASS | 0 | Notification Service 99 tests; common-web dependency 6 tests |
| Identity regression | PASS | 0 | Identity Service 67 tests; common-web dependency 6 tests |
| Shared module | PASS | 0 | common-web 6 tests |
| Full backend reactor | PASS | 0 | 11 Maven modules, 448 tests, zero failures/errors/skips |
| End-to-end scope | PARTIAL | 0 | Booking outbox production, Identity routing/authorization, and Notification RabbitMQ consumption are verified in companion suites. The combined Notification flow stubs the Identity recipient-client boundary; no full multi-service deployment was launched |

**Overall**: PASS for the S7-08 repository verification gate. Stage 7 was subsequently accepted by the user.

## Legacy Test Migration

| Decision | Source | Details |
|---|---|---|
| Reuse existing tests; add focused integration coverage | Repository | Existing Java/JUnit, Spring Boot, PostgreSQL, and RabbitMQ tests are compatible; no browser application is in scope |

## Test Evidence

The new integrated event-flow test is `NotificationEventListenerRabbitPostgresTests.secretaryRequestThenAdminApprovalRoutesNotificationsToScopedAdminAndRequesterOnce`. It publishes a version-2 Secretary `BookingRequested` with `SCOPED_ADMIN_REQUEST`, then a `BookingApproved` event. It verifies the real RabbitMQ listener and PostgreSQL persistence route the request to the scoped Admin and the decision/comment to the requester, retain correlation/causation, and do not duplicate receipts or notifications on replay.

The combined flow stubs only the Notification-to-Identity recipient-client boundary. The Identity HTTP client contract and real Identity permission/scope decisions are exercised separately; Booking PostgreSQL tests verify transactional source outbox facts. This report does not claim production-like deployment across all services.

| Exact command | Result |
|---|---|
| `mvn -f backend/pom.xml -pl notification-service -am -Dtest=NotificationEventListenerRabbitPostgresTests -Dsurefire.failIfNoSpecifiedTests=false test` | Exit code 0; 6 passed, 0 failed, 0 errors, 0 skipped |
| `mvn -f backend/pom.xml -pl notification-service -am test` | Exit code 0; Notification 99 passed and common-web 6 passed; zero failures/errors/skips |
| `mvn -f backend/pom.xml -pl identity-service -am test` | Exit code 0; Identity 67 passed and common-web 6 passed; zero failures/errors/skips |
| `mvn -f backend/pom.xml -pl common-web test` | Exit code 0; 6 passed, zero failures/errors/skips |
| `mvn -f backend/pom.xml test` | Exit code 0; 448 tests across all 11 Maven modules passed; zero failures/errors/skips |

Surefire reports are under each module's `target/surefire-reports/`.

## Environment and Coverage

```text
environment:
  docker: AVAILABLE — docker info succeeded; Docker-backed Testcontainers PostgreSQL and RabbitMQ ran
  node: AVAILABLE — Node.js v22 installed
  playwright: NOT APPLICABLE — API/messaging backend; no browser-rendered UI
  infra-tier: PRIMARY(Docker-based) — Testcontainers PostgreSQL and RabbitMQ
  browser-tier: NOT APPLICABLE — API-only service; framework integration testing used
startup: PASS — Spring Boot test contexts started; readiness exercised through the running RabbitMQ listener and database-backed integration tests
integration: PASS — exit_code: 0, passed: 448, failed: 0, skipped: 0, full backend reactor with Testcontainers
e2e: PARTIAL — exit_code: 0, 6 focused integration tests passed; the notification workflow is exercised end-to-end through RabbitMQ and PostgreSQL, while the Identity recipient-client boundary is stubbed and verified separately
overall: PASS — S7-08 repository tests pass; no full deployed cross-service environment was claimed
```

The first execution of the newly added flow test exposed a missing active `BookingApproved` test template. The test fixture was corrected, the focused class reran successfully, and both the complete Notification suite and full reactor passed afterward. No production code, ERD, or migration change was needed for S7-08.

## Issues Found

| # | Severity | Description | Status |
|---|---|---|---|
| 1 | Test fixture | New approval-flow integration test initially lacked an active `BookingApproved` template | Corrected; focused and complete suites reran successfully |
