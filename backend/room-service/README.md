# RRA RoomIQ Room Service

## Current scope

S4-01 through S4-12 are complete, verified, and accepted by the user. Flyway V1 creates the Room Service-owned room/resource schema from the approved ERD, V2 adds case-insensitive building-scoped room-name/code indexes, V3 enforces rule scope ownership, Room writes delegate authorization to Identity, all 31 `/api/v1` operations are documented, and focused/cross-stage verification passes. Stage 5 S5-06 consumes Room's protected owner APIs for availability candidates; Scheduling does not access Room persistence directly. Booking's S6-06 recurring confirmation also reads Room's protected maintenance-period API to validate every evaluated occurrence; Room persistence and APIs are unchanged.

## Persistence boundary

Room Service owns `room`, `room_type`, `facility_type`, `room_facility`, `room_photo`, `room_rule`, `room_rule_allowed_department`, `room_status_history`, `maintenance_period`, and the approved local `room_event_outbox`. Foreign keys are limited to relationships within those Room-owned tables. `floor_id`, `office_building_id`, `department_id`, and user/actor IDs are UUID references only; this service does not own or directly access Organization or Identity tables.

Room capacity must be positive. Room names and codes are case-insensitively unique within a building. Type/facility codes are unique. Facility assignments are unique per room/type; quantity must be 1 through 32767, state must be `WORKING`, `FAULTY`, or `REMOVED`, and `lastServicedAt` is optional. Inactive facility types cannot be assigned. Rule and status values are constrained, and timestamps use `TIMESTAMP WITH TIME ZONE`.

The `domain/entity` package contains the nine persistence entities and the Room-owned enums; `domain/repository` contains their Spring Data repositories; `domain/dto` contains persistence-independent response records; `domain/mapper/RoomMapper` maps entities to those DTOs; and `integration` contains the Organization API client used to verify building/floor references. The Room entity model uses JPA relationships only for Room-owned rows. Building, floor, department, and user/actor identifiers remain scalar UUID fields.

Facility assignments are managed at `/api/v1/rooms/{roomId}/facilities` with GET, POST, and PUT. The nested room scope is checked, the referenced facility type must exist and be active, and one facility type may be assigned only once per room. Updates may change the assigned type only when that does not create a duplicate. Assignment responses include the Room Facility ID, room/type IDs, quantity, state, and last-serviced timestamp.

Room rules are managed through `/api/v1/rooms/{roomId}/rules` for room-specific policies and `/api/v1/office-buildings/{officeBuildingId}/room-rules` for building defaults, with GET/POST on both paths and GET/PUT at `/api/v1/room-rules/{ruleId}`. A rule belongs to exactly one scope. Policy input validates positive duration bounds, nonnegative advance/cancellation/release-buffer values, minimum/maximum ordering, all four required flags, active state, optional effective time, and up to 100 allowed department IDs. An omitted release buffer defaults to five minutes; an empty department list means unrestricted. Department references are checked through Organization's authenticated API, must be active, and building-scoped departments must match the rule's building. Global departments with no building scope are allowed.

Room photos use `/api/v1/rooms/{roomId}/photos`: GET lists, POST uploads multipart `file` with optional ordering/primary/public-approval metadata, PUT updates those metadata fields, and DELETE removes the provider asset and Room row. Photo mutations/listing delegate the caller's bearer token to Identity. All Room writes use Identity's `/api/v1/internal/authorization/room-management`: building-scoped writes require active-user `ROOM_MANAGE` for every affected building; unscoped catalog writes require global system-admin authority. JPEG, PNG, and WEBP MIME types must match their file signatures; default maximum size is 5 MiB and default maximum count is 10 photos per room. These limits can be overridden with `ROOM_PHOTO_MAX_FILE_SIZE_BYTES` and `ROOM_PHOTO_MAX_COUNT`; servlet multipart limits are configurable with `ROOM_PHOTO_MAX_FILE_SIZE` and `ROOM_PHOTO_MAX_REQUEST_SIZE`. Cloudinary upload/delete calls are isolated behind `CloudinaryPhotoStorage`; its cloud name, API key, API secret, and folder are configured through environment variables. Uploaded bytes are not persisted by Room Service.

`MaintenancePeriod.period` maps PostgreSQL `TSTZRANGE` through a Hibernate `UserType` using JDBC `Types.OTHER`; Hibernate schema validation and the repository round-trip test cover this mapping. No Room controllers or endpoints were added under S4-02.

Maintenance periods use PostgreSQL `TSTZRANGE`. The migration installs `btree_gist` and adds a GiST exclusion constraint so one room cannot have overlapping maintenance periods; adjacent half-open periods are allowed. The database role applying Flyway migrations must be permitted to create this extension. The API contract and ERD were not changed.

## Runtime and verification

### S4-08 Room lifecycle

- `PATCH /api/v1/rooms/{id}/status` atomically persists a new status and `RoomStatusHistory`; same-state transitions are rejected and `DECOMMISSIONED` is terminal. `PUT /api/v1/rooms/{id}` cannot change status.
- Stable lifecycle errors include `ROOM_STATUS_TRANSITION_INVALID`, `ROOM_STATUS_CHANGE_REQUIRED`, `ROOM_DECOMMISSIONED`, `ROOM_MAINTENANCE_PERIOD_INVALID`, `ROOM_MAINTENANCE_PERIOD_OVERLAP`, and `ROOM_MAINTENANCE_PERIOD_NOT_FOUND`.
- `GET /api/v1/rooms/{id}/status-history` returns chronological history and remains available for a soft-deleted room. `DELETE /api/v1/rooms/{id}` sets `deleted_at`; standard room reads/lists and child operations hide deleted rooms while retaining their rows and related history.
- `GET/POST /api/v1/rooms/{roomId}/maintenance-periods` lists or creates finite half-open ranges; `DELETE /api/v1/rooms/{roomId}/maintenance-periods/{maintenancePeriodId}` removes a scheduled period. PostgreSQL's existing `btree_gist` exclusion constraint rejects same-room overlaps; adjacent ranges and overlapping ranges for different rooms are allowed. S6-06 Booking confirmation consumes the existing authenticated GET owner API and fails closed if returned maintenance data is malformed or belongs to a different room.
- Status requests require `status` and nonblank `reason`. Maintenance requests require `startsAt`, `endsAt`; `reason` is optional. Actor UUIDs are returned by Identity after authorization and cannot be supplied by Room callers.
- The Room/Testcontainers suite passed 19 tests before S4-09; the final combined Room/API-client suites passed 26 tests. Identity/common-web passed 41 tests and the full backend reactor passed 28 suites/101 tests, with zero failures/errors/skips. S4-08 required no ERD or migration change.
- The S4-08 Room/Testcontainers gate passed 19 tests; final S4-10 Room/Testcontainers suite passed 24 tests. With four Identity-client tests, the focused Room/client suites passed 28 tests. Identity/common-web passed 41 tests and the full backend reactor passed 28 suites/103 tests, with zero failures/errors/skips. S4-08 required no ERD or migration change.

### S4-09 Room mutation authorization

- Room, facility assignment, room-rule, photo, status, soft-delete, and maintenance mutations call Identity's `POST /api/v1/internal/authorization/room-management` before writes. The caller's bearer token is forwarded, and Identity checks that the account is active and has `ROOM_MANAGE` for the target building (or global system-admin authority).
- Room moves require authorization in both the current and destination buildings. Room/facility type catalog writes have no building scope and therefore require global system-admin authority.
- Status-history and maintenance actor IDs are taken only from Identity's response. Request DTOs contain no actor IDs. Missing/invalid bearer tokens, denial, malformed Identity responses, and Identity outages fail closed before Room persistence or Organization reference lookups.
- Authorization is enforced in Room service methods, not only in controllers, so non-HTTP callers cannot bypass the policy. Reads other than existing photo listing remain for S4-10's documented endpoint policy.
- Focused Room/Testcontainers and authorization-client suites passed 26 tests; Identity/common-web passed 41 tests; full backend reactor passed 28 suites/101 tests with zero failures/errors/skips.
- Focused S4-09 Room/Testcontainers and authorization-client suites passed 26 tests; the final S4-10 Room/Testcontainers suite passed 24 tests. Identity/common-web passed 41 tests and the full backend reactor passed 28 suites/103 tests with zero failures/errors/skips.

### S4-10 API contract

All 31 `/api/v1` operations are published under `/v3/api-docs` with the Identity bearer/JWT scheme, shared `ApiResponse` success schema, shared `ApiError` error schema, and the optional `X-Correlation-ID` header documented on requests and responses. Request/response types remain Room-owned DTOs. The contract documents stable validation, authentication, authorization, not-found, conflict, provider, and dependency error codes. Runtime validation failures and domain conflicts are verified to return the shared `ApiError` envelope with the same correlation ID echoed in the response header.

Runtime database settings are `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD`; the default local database is `RRA_RoomIQ` on PostgreSQL. `ORGANIZATION_SERVICE_URL` configures the Organization API base URL and defaults to `http://localhost:8082`; `IDENTITY_SERVICE_URL` configures the Identity API base URL and defaults to `http://localhost:8081`. Room create/update validates building/floor through Organization after Identity authorization. All Room writes forward the caller's bearer token to Identity; Cloudinary credentials, folder, photo max size/count, and multipart limits are externalized in `backend/.env.example`. Flyway runs from `classpath:db/migration` at service startup. Local PostgreSQL/RabbitMQ/Redis infrastructure is described in `../infrastructure/docker-compose.yml`.

Migration and PostgreSQL-specific integrity tests use Testcontainers and require Docker:

```bash
mvn -f backend/pom.xml -pl room-service -am -Dtest=RoomServiceApplicationTests,HttpOrganizationDirectoryClientTests,HttpRoomPhotoAuthorizationClientTests,CloudinaryPhotoStorageAdapterTests -Dsurefire.failIfNoSpecifiedTests=false test
```

The focused suites verify all nine tables, Flyway V1-V3, local foreign-key boundaries, positive capacity, building-scoped uniqueness, facility assignment behavior, rule bounds and scope, photo upload/update/delete, image signature/MIME/size/count rejection, scoped authorization forwarding, and fail-closed provider/Identity behavior. S4-07 focused Room/adapter/auth tests passed 26 tests (16 Room/Testcontainers, 2 Cloudinary adapter, 3 photo-authorization client, and 5 Organization-client tests); Identity/common-web passed 35 tests and the clean full backend reactor passed 28 suites/94 tests with zero failures/errors/skips. Existing ERD photo fields were sufficient, so no ERD change was made.

### S4-11 implementation verification

- The 27-test PostgreSQL Testcontainers suite verifies Flyway V1-V3, PostgreSQL constraints and foreign-key ownership, repository mappings, API/service behavior, authorization, uniqueness/capacity, soft deletion, rules, facilities, photos, status history, maintenance overlap, and shared error/correlation responses.
- Cloudinary adapter tests cover successful upload/delete, missing configuration, malformed upload metadata, provider upload/delete failures, and safe dependency errors. API tests verify 502 invalid-metadata and 503 provider-failure envelopes with correlation IDs.
- Room photo upload tests verify provider-asset cleanup when metadata is invalid or the Room database insert fails, while preserving an existing database row. Verification exposed and fixed the missing cleanup path for invalid provider metadata.
- Focused Room/Testcontainers and adapter/client suites passed 39 tests; Identity/common-web passed 41 tests; the full reactor passed 28 suites/107 tests, all with zero failures/errors/skips. No requirements, migration, or ERD change was needed.

### S4-12 Documentation and acceptance readiness

Room runtime settings are externalized in `backend/.env.example`: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `ROOM_SERVICE_PORT` (default `8083`), `ORGANIZATION_SERVICE_URL`, `IDENTITY_SERVICE_URL`, Cloudinary cloud/key/secret/folder, photo file-size/count limits, and servlet multipart limits. The matching defaults and Flyway/schema-validation settings are in `src/main/resources/application.yml`. Room photo binaries are provider-managed; Room stores the asset ID and approved metadata.

## S7-01 operational event production

Room status and maintenance mutations write versioned `RoomStatusChanged` and `RoomMaintenanceChanged` events into the approved Room-owned `ROOM_EVENT_OUTBOX` in the same PostgreSQL transaction as their source mutation. The scheduled relay publishes persistent messages to the v1 RabbitMQ exchange with correlated publisher confirms, mandatory-return handling, and retryable delivery; at-least-once consumers deduplicate by `eventId`. Both Room events use aggregate type `ROOM`.

The relay records the bounded Micrometer counter `roomiq.room.outbox.delivery` with `outcome=confirmed|deferred` for broker-confirmed/routed and retryable deliveries. This does not add a metrics endpoint, exporter, or scrape configuration; those remain deployment requirements. Interrupted confirm waits defer the durable event and restore the worker thread's interrupt status. If a transaction ends before marking a claimed event published, the row remains eligible for a later claim.

Before emitting these events, Room calls Booking's protected `POST /api/v1/internal/notifications/room-event-recipients`, forwarding the caller bearer token. Identity authorizes `ROOM_MANAGE` for the supplied building. Booking returns distinct organizers and internal participants on `CONFIRMED`/`IN_PROGRESS` occurrences whose buffered occupied interval overlaps the maintenance period; for a status change, lookup starts at the event instant and has no upper bound. External attendees are not included. Invalid or unavailable lookup responses fail the Room operation; Room does not emit an event with a silently empty or fabricated audience. The endpoint contract is in `../api-contracts/rra-roomiq-stage1-contract.json`.

The Room Service uses `BOOKING_SERVICE_URL` (default `http://localhost:8085`) for recipient lookup and supports distinct `ROOM_RABBITMQ_USERNAME`/`ROOM_RABBITMQ_PASSWORD` publisher credentials, falling back to shared local RabbitMQ settings for development. Production broker accounts and per-routing-key publish permissions still require deployment configuration and verification.

S7-01 Room producer/recipient verification passed 35 tests. Identity passed 53 tests, common-web 6 tests, and the full 11-module reactor passed 61 suites/354 tests, all with zero failures, errors, or skips. S7-01 is complete at the repository level; production publisher credentials and routing-key permissions remain an external deployment gate.

`backend/api-contracts/rra-roomiq-room-postman-collection.json` provides examples for all 31 Room operations with inherited bearer auth, correlation IDs, safe UUID placeholders, representative request bodies, and chained resource IDs. Validate its route inventory and headers with `node api-contracts/validate-room-postman-collection.mjs` from `backend/`.

Final gates passed: focused Room/Testcontainers and adapter/client suites (39 tests); Identity/common-web (41 tests); full Maven reactor (28 suites/107 tests), all with zero failures/errors/skips. The Room Postman validator also passed. S4-12 is complete; explicit Stage 4 user acceptance remains pending.