# RRA RoomIQ Identity Service Documentation

## Current status

The identity service is the first domain service in the Stage 1 multi-service foundation. It consumes the shared `common-web` module for response envelopes, validation errors, exception handling, correlation IDs, logging, and OpenAPI defaults.

## Service purpose

This service owns the identity and authorization domain for the RRA RoomIQ platform. It is responsible for:

- user lifecycle and credentials
- role and permission management
- privilege evaluation such as CG_BOOKING
- session handling and login/logout flows
- authorization and access scope decisions
- audit-facing identity events

Notification Service delegates operational access checks to Identity. The hidden contact-resolution endpoint returns only the stored email for an active account to a short-lived service token with the dedicated `notification:delivery-contact-resolution` permission. The bearer-protected `/api/v1/internal/authorization/notification-delivery` decision permits delivery health, dead-letter inspection, and redrive only to an active global Super Admin assigned the explicit `NOTIFICATION_DELIVERY_MANAGE` permission. Flyway V4 adds that permission to the catalog but does not assign it to a role automatically.

The bearer-protected `POST /api/v1/internal/authorization/audit-search` decision derives the actor from the validated JWT subject and checks active Identity records for the explicit `AUDIT_READ` permission. An active unscoped `SUPER_ADMIN` receives global access; an active `ADMIN` receives only its persisted non-null building assignments, and an empty scope set is denied. The endpoint accepts no authorization facts in its request body. Flyway V5 adds `AUDIT_READ` to the catalog without granting it to `ADMIN` or `SUPER_ADMIN`. Identity stores building IDs as references and does not verify current building lifecycle state against Organization Service.

## Technology stack

- Java 25
- Spring Boot 4.1.1
- Spring Framework 7.x
- Spring Data JPA
- PostgreSQL driver
- Flyway
- Spring Security
- Spring Boot Actuator
- H2 for test runtime isolation
- Maven wrapper

## Runtime configuration

The service reads environment values externally via Spring config import from `.env` and supports local development defaults. Required variables include:

- DB_URL
- DB_USERNAME
- DB_PASSWORD
- SERVER_PORT
- JWT_SECRET
- additional service and external integration values as needed

The local database is configured for PostgreSQL by default and uses a safe development fallback while the application is initialized.

## Stage 1 foundation summary

The repository currently includes:

- backend parent Maven project
- common-web shared standards module
- identity-service as the first domain service module
- shared backend docs
- shared infrastructure folder
- environment placeholder file for local backend setup
- documented multi-service architecture baseline
- shared response, error, validation, logging, correlation, and OpenAPI conventions

## Stage 2 progress

### S2-01 Identity schema migration: complete

Flyway migration `V1__identity_schema.sql` creates the identity-owned tables:

- `app_user`
- `role`
- `permission`
- `role_permission`
- `user_role`
- `user_privilege`
- `user_session`

The migration includes UUID primary keys, required and uniqueness constraints, lifecycle status checks, optimistic versioning, soft deletion, session expiry checks, privilege validity checks, indexes, and only identity-owned foreign keys. Organization references remain ID-only as required by the service ownership model.

### S2-02 Identity domain entities: complete

Implemented JPA entities and the `UserStatus` enum for all seven identity tables. Relationships follow the migration and ERD, including role-permission assignments, user roles, privileges, sessions, and grant-auditor references. `AppUser` provides optimistic locking through `@Version` and soft-delete filtering through `deleted_at`; audit timestamps and defaults are initialized before persistence. Organizational references remain UUID fields because those records belong to other services.

### S2-03 Identity repositories: complete

Spring Data repositories cover users, roles, permissions, role-permission assignments, user-role assignments, privileges, and sessions. H2 repository tests verify lookup, status, scope, relationship, active privilege/session, refresh-token, and soft-delete queries.

### S2-04 User request/response DTOs: complete

Validated DTOs cover user creation, partial profile updates, status changes, list filters/pagination, and response bodies. Responses exclude passwords and password hashes. Bean Validation failures use the shared `ApiError` and structured `ValidationError` contract.

### S2-05 User management APIs: complete

The controller exposes `POST /api/v1/users`, `GET /api/v1/users`, `GET /api/v1/users/{id}`, `PATCH /api/v1/users/{id}`, `PATCH /api/v1/users/{id}/status`, and `DELETE /api/v1/users/{id}`. Create defaults to `PENDING`; user listing supports search, status, department, office, and pagination; delete is soft-delete. Optional passwords are BCrypt encoded before persistence. Success responses use `ApiResponse<T>` and validation, conflict, and not-found responses use `ApiError`.

### S2-06 Role and permission management: complete

Implemented dynamic role and permission creation/listing, role-permission grant/list/revoke, and scoped user-role assign/list/revoke operations. Duplicate role codes, permission codes, role-permission pairs, and user-role assignments in the same scope are rejected with `409` and the shared `ApiError` envelope. User-role grants lock the user row while checking and creating an assignment; role-permission pairs are additionally protected by the ERD-aligned unique database constraint.

### S2-07 Privilege management: complete

Implemented privilege grant, list, evaluate, and revoke operations for codes including `CG_BOOKING`. A grant is effective only when `is_active` is true, the user status is `ACTIVE`, and the evaluation instant is within the half-open validity window `[validFrom, validTo)`; either bound may be absent. Invalid or overlapping active windows are rejected, adjacent windows are allowed, and revocation deactivates rather than deletes the grant to preserve history. Revoked windows may be granted again.

### S2-08 Password security: complete

Passwords are encoded with BCrypt work factor 12 before persistence and are never included in user response DTOs or logged by the user-management flow. Optional passwords must be 12–72 printable ASCII characters and include lowercase, uppercase, a digit, and a symbol; the limit avoids BCrypt's 72-byte truncation behavior. Spring Security loads identity accounts by email and enables authentication only for `ACTIVE` accounts with a password hash; `PENDING`, `SUSPENDED`, and `DISABLED` accounts fail closed. Credential expiry, reset/recovery, and login throttling remain future credential-lifecycle work.

### S2-09 Authentication: complete

The identity service implements `POST /api/v1/auth/login`, `/refresh`, and `/logout`. Login uses a generic unauthorized response for missing users, bad passwords, or non-active accounts and performs a dummy BCrypt check for unknown users. Access tokens are HS256 JWTs with a 15-minute lifetime; opaque refresh tokens are generated from cryptographic randomness, stored only as SHA-256 hashes, and rotated on refresh. Logout revokes the refresh session, and every access JWT is checked against its active, unexpired session so logout immediately disables it. Refresh replay/expired tokens are rejected. `JWT_SECRET` is mandatory and must contain at least 32 bytes; it has no production fallback.

### S2-10 Session management: complete

`USER_SESSION` persists only the SHA-256 hash of each opaque refresh token, plus issuance/expiry/revocation timestamps, IP address, and user agent. Refresh locks and revokes the previous session before issuing a replacement; logout revokes the current session; expired or account-ineligible refresh sessions are durably revoked and rejected. Access JWT validation checks the referenced session and owning user remain active. H2 integration tests assert the persisted metadata, seven-day expiry, rotation, logout, and expired-session revocation. The ERD already contains the required columns and was not changed.

### S2-11 Authorization policies: complete

`IdentityAuthorizationService` centralizes backend authorization for identity, role, privilege, booking, and room operations. Method-security guards reload the active account and current database permissions on each request, deny by default, and enforce `USER_ROLE.scope_office_building_id`. Super Admin has global scope; scoped Admins may manage Secretaries and unassigned pending accounts only within their building, cannot manage role/permission definitions or privileged accounts, and may grant/revoke only `CG_BOOKING` within scope. Secretaries retain self-profile and eligible own-booking access; booking decisions also enforce actor ownership, department/building scope, active status, and time-bounded privilege validity. Scoped user-list queries exclude Admin/Super Admin accounts and out-of-scope users.

Privilege grants record the authenticated actor, not a client-supplied grantor ID. `AccessDeniedException` returns the shared HTTP 403 `ApiError`. `IdentityAuthorizationTests` covers cross-building denial, list filtering, role escalation, definition protection, privilege-code and grantor enforcement, live permission revocation, booking rules, and Super Admin global access. The policy matrix, stable permission catalog, and one-time Super Admin operator bootstrap are documented in [AUTHORIZATION.md](AUTHORIZATION.md). No ERD change was required.

For Stage 6, Identity exposes the bearer-protected `POST /api/v1/internal/authorization/booking` decision endpoint. It evaluates current active-account status, Booking permissions, department/building scope, persisted requester ownership for draft submission and self-approval prevention, effective `CG_BOOKING` privilege, Admin/Super Admin direct-booking authority, and reservation-lifecycle authority. `RESERVATION_LIFECYCLE` allows organizers with `BOOKING_LIFECYCLE_OWN` only when the supplied facts match their current department and building; a scoped administrator needs `BOOKING_LIFECYCLE_MANAGE` for the persisted reservation building, and `IDENTITY_SYSTEM_ADMIN` is global. The actor ID is taken from the validated token; action-specific request facts must not contain caller-supplied actor, role, permission, or status values. Booking forwards the caller token and uses the returned actor ID rather than trusting an API payload. For an authorized APPROVE decision, Identity additionally returns the active persisted requester's ID and trusted display name (display name, falling back to full name); lifecycle authorization echoes the persisted organizer ID for consistency validation. No profile data is returned for other actions. Booking request create/list/get, draft submission, scoped decisions, direct booking, check-in, and completion authorization use this endpoint. It remains internal integration.

For S6-08 and S6-11, Identity exposes the bearer-protected `POST /api/v1/internal/authorization/meeting` decision endpoint. `READ` allows the current active organizer in matching department/building scope or a current in-scope reviewer; `INTERNAL` visibility additionally allows active accounts assigned to the meeting building. `PUBLIC` visibility does not bypass the protected Booking APIs; the separate S6-11 public-card route uses a share token and does not change Identity's meeting authorization policy. `MANAGE` is restricted to the active in-scope organizer or a current scoped reviewer. For internal participant additions, Identity verifies that the target account is active and assigned to the same building and returns its trusted display name. The response echoes persisted owner, scope, visibility, and target-user identifiers for Booking consistency checks. Identity does not accept caller-supplied actor, role, or scope and does not return target profile data on read-only decisions.

For S6-09, the Booking authorization decision also returns a server-derived cancellation authority: `OWNER` for the active booking owner with `BOOKING_CANCEL_OWN` in their current building, `ADMIN` for an active in-scope actor with `BOOKING_CANCEL`, or `SYSTEM_ADMIN` for global Identity system administration. The action remains bearer-protected and returns the authenticated actor and persisted owner IDs; Booking uses this authority to enforce owner/admin cancellation rules and deadline overrides. Identity does not trust an actor, role, or scope supplied by Booking's caller.

### S2-12 Spring Security route policy: complete

`IdentitySecurityConfiguration` defines a stateless OAuth2 bearer-JWT filter chain, disables form login, HTTP Basic, Spring's generated logout endpoint, and CSRF for the token-based API. Public routes are limited to `POST /api/v1/auth/login`, `/refresh`, `/logout`, OpenAPI/Swagger resources, and `/actuator/health`; every other route requires authentication, with method security applying the S2-11 permission and scope policy after authentication. Missing/invalid credentials return HTTP 401 `AUTHENTICATION_REQUIRED`; authenticated authorization failures return HTTP 403 `ACCESS_DENIED`, both using the shared `ApiError` envelope and correlation ID. Focused tests verify the public allowlist, protected identity/unknown/default routes, docs and health access, and login without a CSRF token. No ERD change was required.

### S2-13 OpenAPI documentation: complete

The identity application publishes the `bearerAuth` HTTP bearer/JWT security scheme. User, role/permission, privilege, and internal authorization operations are tagged and each operation has a summary and description; protected operations require bearer auth, while login, refresh, and logout are public. Springdoc exposes request/response DTO schemas and documented validation, authentication, authorization, not-found, and conflict responses. The integration test inspects `/v3/api-docs`, verifies all 31 operations and their security requirements, including Booking, meeting, Room, Scheduling, and Audit Search authorization endpoints, checks the JWT scheme and request schemas, and confirms public auth operations carry no bearer requirement. No ERD change was required.

### S2-14 Error and audit behavior: complete

Identity business failures use the shared `DomainException` and `ApiError` contract with stable codes, including `AUTHENTICATION_FAILED`, `INVALID_REFRESH_TOKEN`, `USER_NOT_FOUND`, `DUPLICATE_USER_IDENTIFIER`, `ROLE_CODE_CONFLICT`, `PERMISSION_CODE_CONFLICT`, `ROLE_PERMISSION_CONFLICT`, `USER_ROLE_CONFLICT`, `PRIVILEGE_GRANT_CONFLICT`, and `INVALID_PRIVILEGE_VALIDITY_WINDOW`. Spring Security retains `AUTHENTICATION_REQUIRED` and `ACCESS_DENIED`; validation errors retain structured field details. Unexpected errors return `INTERNAL_ERROR` without stack traces or exception messages.

`CorrelationIdFilter` accepts only 1-128 ASCII letters, digits, dots, underscores, or hyphens. Invalid/missing values are replaced once and the same value is used in request state, MDC, response header, and `ApiError`.

Identity audit records use a technical `identity_audit_outbox` table added by Flyway `V2__identity_audit_outbox.sql`; it is not an `AUDIT_EVENT` domain table and does not change the ERD. The identity transaction writes the outbox row atomically with the user, role, permission, assignment, privilege, or session mutation. Login success/failure, refresh rotation/rejection, logout, authorization denial, user lifecycle, role/permission changes, role assignments, and privilege changes are represented with event ID/type, timestamp, source service, actor/resource/scope IDs where available, outcome, correlation ID, and safe metadata.

Every event envelope includes `schemaVersion: 1`. The scheduled relay publishes persistent JSON messages through exchange `roomiq.events.v1` with routing key `audit.identity` to durable queue `roomiq.audit.identity.v1`. It requires a positive correlated publisher confirm and rejects mandatory returns before marking an event published. Failed, timed-out, NACKed, and unroutable sends remain in the outbox and receive capped exponential retry delays. Delivery is at least once; the event ID is the consumer deduplication key. The accepted S8-02 Audit Service consumer validates the Identity envelope and persists it to the Audit-owned store without cross-service database access. No password, password hash, token, email, or profile value is included in audit metadata. Logging of unexpected errors and broker failures avoids exception messages and stack traces.

Identity and Audit use separate databases and service credentials. Identity publishes only its outbox events; Audit consumes the broker contract and does not query Identity tables.

Focused tests cover stable errors, invalid/valid correlation IDs, audit payload redaction, and successful/retried relay delivery. No ERD change was required.

### S2-15 Automated identity tests: complete

The identity test suite is organized by behavior and runs reproducibly through the backend Maven reactor:

- Unit tests: shared domain error mapping, correlation filtering, and audit outbox relay confirm/retry behavior.
- Repository and migration tests: identity relationships, soft-delete queries, Flyway V1/V2 history, and outbox insert/read persistence on isolated H2 databases.
- Controller tests: user lifecycle, role/permission and user-role management, privilege grant/evaluation/revocation, stable error codes, validation, and safe response DTOs.
- Authentication tests: public/protected route policy, BCrypt/status rejection, successful login, session metadata, refresh rotation/replay/expiry, logout revocation, and OpenAPI security definitions.
- Security and authorization tests: role/permission/privilege enforcement, cross-building denial, Super Admin access, scope-filtered lists, and persisted authorization-denial audit events.
- Audit assertions: successful/failed login, refresh/replay/logout, user lifecycle, role/permission/assignment, privilege changes, correlation/schema version, and sensitive-data exclusion.

The clean identity/common-web suite passed 12 Surefire suites and 37 tests with zero failures or errors. Tests use H2 and mocked RabbitMQ confirms; no live PostgreSQL or RabbitMQ integration environment is part of this stage.

The importable Postman collection is validated with:

```bash
node api-contracts/validate-identity-postman-collection.mjs
```

This checks endpoint coverage, collection variables, protected-request headers, chained token/resource capture, reserved sample domains, and secret-marker hygiene.

### S2-16 Postman contract and sample data: complete

`api-contracts/rra-roomiq-identity-postman-collection.json` is a Postman v2.1 collection covering all 23 implemented identity operations plus health and OpenAPI support. Collection variables provide the local base URL, bearer/refresh tokens, resource IDs, organization-scope IDs, and correlation ID. Login and refresh scripts rotate tokens; create-role, create-permission, create-user, role-permission, user-role, and privilege requests capture IDs for subsequent requests.

All protected requests include bearer authorization and `X-Correlation-ID`. Sample credentials use placeholders and a reserved `example.test` identity; no real secrets, database credentials, provider keys, or production identifiers are included. The collection validator is the reproducible import-contract check for S2-16.

### S2-17 SRS/ERD and documentation synchronization: complete

The identity implementation is mapped to the authoritative Backend SRS, Final SRS, and Mermaid ERD in [identity-srs-erd-alignment.md](../docs/identity-srs-erd-alignment.md). The seven identity ERD entities, APIs, security model, migrations, audit boundary, Postman contract, tests, configuration, and known deferred items are synchronized across the backend roadmap, identity documentation, authorization guide, common-web README, API-contract README, stage contract JSON, and changelogs.

The approved SRS and ERD were not modified. The identity audit outbox remains service-local infrastructure; `AUDIT_EVENT` persistence remains owned by the Audit Service as required by the microservice ownership model.

### S2-18 Stage 2 verification gate: complete

The clean identity/common-web verification passed 12 Surefire suites and 37 tests with zero failures or errors. The clean full backend reactor passed 20 Surefire suites and 45 tests with zero failures or errors at the S2-18 gate. This confirms the identity implementation, shared Stage 1 conventions, service smoke tests, migrations, security, authorization, audit behavior, and Postman-contract support at that gate. Stage 3 S3-01 through S3-11 verification is complete and has since been accepted.

### S3-06 Organization authorization

`POST /api/v1/internal/authorization/organization` is bearer-protected and evaluates `READ` or `MANAGE` against the authenticated account's current database-backed state. Active users may read organization data; organization mutations require the unscoped `IDENTITY_SYSTEM_ADMIN` permission. Organization Service forwards the caller's bearer token and fails closed if Identity is unavailable. The endpoint does not accept user IDs, roles, scopes, or permissions from request data.

### S5-02 Scheduling authorization

`POST /api/v1/internal/authorization/scheduling` is bearer-protected. Active users may read calendars, working windows, holidays, closure periods, recurrence rules, constraints, and availability; `MANAGE` requires the current global `IDENTITY_SYSTEM_ADMIN` permission. Its response includes the authenticated `actorUserId`, which Scheduling uses for creator attribution. Scheduling forwards the original Identity-issued bearer token and fails closed if Identity is unavailable. The endpoint accepts only the requested action, not client-supplied identity, role, scope, or permission claims. It authorizes the token's user; it is not a separate machine/service-identity credential. Scheduling's business routes are not Gateway-routed in Stage 5. The current Identity API inventory is 27 OpenAPI operations after adding Booking authorization.

### S3-08 Organization reference ownership

Identity stores `department_id`, `office_building_id`, and `scope_office_building_id` as UUID scalars only. Identity does not own Organization tables, map Organization JPA entities, or declare cross-service foreign keys. `IdentityRepositoryTests.organizationReferencesRemainIdentifierOnlyAcrossServiceBoundary` verifies that unbacked Organization identifiers persist and that imported foreign-key metadata excludes those columns. Organization remains responsible for its own hierarchy foreign keys. No ERD change was required.

### S3-11 Stage 3 verification
S4-09 is complete: Identity's room-management decision returns the JWT-subject actor ID and supports scoped `ROOM_MANAGE` plus global system-admin catalog decisions. S4-10 Room API contract documentation is complete. Stage 4 S4-01 through S4-12 was explicitly accepted by the user. S5-02 added the Scheduling `READ`/`MANAGE` authorization endpoint; S5-03 and S5-04 use its actor ID for Holiday and recurrence-rule creator attribution. S5-05 constraint validation and S5-06 availability search use Identity `READ` authorization without changing Identity persistence or policy. S5-07 confirmed S5-02's service-hosted calendar/window APIs deny missing credentials and delegate reads/mutations as `READ`/`MANAGE`. Identity authorization/OpenAPI tests and the full backend Java test gate passed. No Identity persistence or ERD change was required; Stage 5 was subsequently accepted by the user. For Stage 6, Identity authorizes Booking request creation/list/read, requester-only draft submission, scoped decisions, and Admin/Super Admin direct-booking authority with current scope and VIP privilege checks for eligible Secretaries. For APPROVE only, it returns the active requester profile's trusted display name to support the initial meeting organizer; Identity data is not accepted from the caller. The submitted, approved, and direct-booking flows revalidate current Organization, Room, and Scheduling policy through the owning services. During S6-04 verification, Identity/common-web passed 51 tests and the full 11-module backend reactor passed 214 tests, with zero failures/errors/skips. The later S6-05 regression passed Identity 47/47, common-web 6/6, and the full 11-module reactor 225/225, with zero failures/errors/skips. No Identity schema or ERD change was required.

The dedicated Identity/common-web reactor passed 35 tests with zero failures/errors/skips. The clean Organization reactor passed 21 tests, and the S3-11 clean backend reactor passed 25 suites/69 tests across all 11 reactor projects with zero failures/errors/skips. No unauthorized ERD or service-boundary changes were made. Stage 3 is accepted. Identity provides the protected `ROOM_MANAGE` building-scope decision used by Room Service photo operations. S4-07 Identity/common-web passed 35 tests and the clean full backend reactor passed 28 suites/94 tests with zero failures/errors/skips.

## Local development notes

1. Use `.env` for local secrets and configuration.
2. Do not commit `.env` or production credentials.
3. Set a strong `JWT_SECRET` of at least 32 bytes in local/production environment configuration; the application has no signing-secret fallback.
4. Keep each service isolated by business ownership and persistence boundary.

## Verification

The identity module and complete backend reactor are verified from the repository root with:

```bash
mvn -q -f backend/pom.xml -pl identity-service -am test
mvn -q -f backend/pom.xml clean test
```

The final clean backend reactor passed 20 Surefire suites and 45 tests with no failures or errors. This includes the S2-14 error, correlation, audit-redaction, and confirmed outbox-retry tests, the S2-15 identity coverage, common-web tests, and all service smoke tests.

The identity test suite runs Flyway against H2, verifies schema migration, entity registration and persistence, repository queries, DTO validation, user, role/permission, privilege, and authentication HTTP behavior, shared response/error envelopes, BCrypt password storage, audit/version defaults, and soft-delete filtering.

### S2-03 Identity repositories: complete

Implemented Spring Data repositories for users, roles, permissions, role-permission assignments, user-role assignments, privileges, and sessions. H2 repository tests verify case-insensitive identity lookups, status and scope queries, relationship existence checks, active privilege/session queries, refresh-token lookup, and soft-deleted user exclusion.

### S2-04 User request/response DTOs: complete

Implemented validated API DTOs for user creation, updates, status changes, list filtering/pagination, individual responses, and paginated list responses. Response DTOs intentionally exclude password hashes. Bean Validation tests verify invalid requests produce the shared `ApiError` contract with `CONSTRAINT_VIOLATION`, request path, correlation ID, and structured field/message details.

## Implementation changelog

### 2026-09-30
- Completed S2-14 stable identity error codes and correlation-ID consistency.
- Added transactional identity audit outbox migration V2 and confirmed/retryable RabbitMQ relay.
- Audited identity authentication, authorization, user, role, permission, role-assignment, and privilege outcomes without including sensitive values.
- Added error, correlation, redaction, relay, and schema-version tests; synchronized the API contract and authorization guidance.
- Completed S2-15 automated identity coverage for repository/migration, controller, security, authentication, authorization, error, and audit behaviors.
- Completed S2-17 SRS/ERD alignment documentation and synchronized all README, API-contract, authorization, and changelog surfaces.
- Completed S2-18 Stage 2 verification gate: identity/common-web and full-reactor tests passed with zero failures/errors.
- Verified the clean identity/common-web suite: 12 suites, 37 tests, zero failures/errors.
- Verified the clean full backend reactor: 20 suites, 45 tests, zero failures/errors.

### 2026-09-28
- Completed Stage 1 backend foundation setup.
- Created backend parent Maven project for shared service governance.
- Added service directories for future microservices.
- Synced backend documentation with the current foundation state.
- Verified the identity service build and test lifecycle.

### 2026-09-27
- Initialized the Spring Boot identity service foundation.
- Fixed PostgreSQL environment and datasource configuration.
- Established Flyway, JPA, security, and actuator configuration.
- Confirmed the service booted successfully.
