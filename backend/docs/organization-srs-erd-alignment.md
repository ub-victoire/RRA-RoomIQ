# Organization Schema and ERD Alignment

## Scope

This note maps S3-01 through S3-11 verification to the approved `Requirements/RRA_RoomIQ_ERD_v2.mmd`. It records schema, persistence-model, hierarchy-integrity, validation, lifecycle, API, authorization, API-contract/OpenAPI, service-ownership, isolated test, documentation, and stage-verification traceability. S3-07 through S3-11 required no ERD change.

## S3-01 mapping

| ERD entity | Organization table | Service-local references | Status |
|---|---|---|---|
| `COUNTRY` | `Country`, `CountryRepository`, create/response DTOs | None | Implemented |
| `PROVINCE` | `Province`, `ProvinceRepository`, create/response DTOs | `country_id` → `country.id` | Implemented |
| `DISTRICT` | `District`, `DistrictRepository`, create/response DTOs | `province_id` → `province.id` | Implemented |
| `OFFICE_BUILDING` | `OfficeBuilding`, `OfficeBuildingRepository`, create/response DTOs | `district_id` → `district.id` | Implemented |
| `FLOOR` | `Floor`, `FloorRepository`, create/response DTOs | `office_building_id` → `office_building.id` | Implemented |
| `DEPARTMENT` | `Department`, `DepartmentStatus`, `DepartmentRepository`, create/response DTOs | Optional office-building and department-parent relationships | Implemented |

The migration adds UUID primary keys, ERD-defined timestamps and lifecycle fields, uniqueness constraints, department status validation, and indexes for hierarchy references. The JPA entities use the same tables/columns, map only organization-owned relationships, and are verified with Hibernate schema validation. The six Spring Data repositories are under `domain/repository`; validated create requests and response DTOs are under `domain/dto`; `OrganizationMapper` maps between DTOs and persistence entities. `working_calendar_id` is stored as a UUID without a foreign key because Working Calendar is outside Organization Service ownership.

## S3-03 hierarchy integrity

The service-local foreign keys enforce Country → Province → District → OfficeBuilding → Floor parent existence. Flyway V2 adds a database check preventing a Department row from naming itself as its parent. `Department.reparent` rejects self/ancestor cycles and parents outside the same nullable building scope. `DepartmentHierarchyService` performs reparenting in a serializable transaction while pessimistically locking the department rows used for the validation, so simultaneous service-mediated reparent operations cannot create a cycle. Missing departments and parent IDs return stable domain errors.

## S3-04 API surface

Controllers expose versioned CRUD, get-by-ID, status, filtering, paging, and sorting routes for all six resources. `GET /api/v1/countries/{id}/hierarchy` returns only the requested country's nested Province → District → OfficeBuilding → Floor/Department tree. List endpoints use server-side specifications, case-insensitive escaped search, bounded page sizes, and allow-listed sort properties. Physical deletion is restricted by foreign keys and returns a stable conflict response when records are referenced.

## S3-05 validation and lifecycle

Flyway V3 adds database checks rejecting blank country/province/district/building/floor/department names, building/department codes, and blank timezone values. V4 rejects malformed timezone path syntax; exact IANA membership remains checked at the API and service layers. Existing `VARCHAR` widths and V1 uniqueness constraints match DTO lengths and ERD scopes; V3/V4 add no columns, tables, or relationships. API DTO validation enforces nonblank/maximum-length fields and optional country ISO alpha-2 syntax. Country ISO values normalize to uppercase; whitespace-only optional ISO values clear to null. Timezone null/blank uses `Africa/Kigali`; explicit values are trimmed and validated at both the API boundary and service layer.

Service writes require active parents for active children. Deactivation of a location/building is rejected when any active descendant exists, including a descendant behind an inactive intermediate legacy row. Department status uses the same rule over the self-referential tree. Row locks serialize parent status changes with child creation/reactivation; department hierarchy updates retain serializable transactions and locked rows. A department with children cannot change building scope (`DEPARTMENT_SCOPE_HAS_CHILDREN`); children must be explicitly handled first. Cross-scope parents and cycles return stable conflict errors.

PUT semantics are field-specific: null `active` preserves current state; optional blank address/ISO values normalize to null; null/blank timezone selects the default; null `workingCalendarId` and department parent clear their optional references. Required names/codes cannot be cleared. Referenced rows remain nondeletable; no hard-delete or cascade behavior was introduced.

Focused tests cover API validation/error envelopes, direct service timezone defense, database nonblank and uniqueness enforcement, column lengths, valid/default/invalid timezones, active parent/descendant cases, legacy-invalid intermediate rows, department subtree moves, update atomicity, and null semantics. No ERD modification was necessary.

## S3-06 delegated authorization

Organization Service intercepts every `/api/v1/**` request and forwards its bearer token to Identity Service at `POST /api/v1/internal/authorization/organization`. GET requests require an active identity account; every mutation requires the current unscoped `IDENTITY_SYSTEM_ADMIN` permission. Scoped Admin access does not authorize location hierarchy changes, consistent with the SRS assigning location CRUD to Super Admin. Missing credentials and denied policy return 401/403; Identity Service failures return 503 and never fail open. Organization Service does not read Identity tables or trust client-provided role/scope claims.

## S3-07 API contract and OpenAPI

All 38 Organization API operations are under `/api/v1` and use service-owned request/response DTOs. `OrganizationOpenApiConfiguration` documents the Identity bearer JWT security requirement, optional `X-Correlation-ID` request header and response header, shared `ApiError` schema for error responses, and stable codes for validation, authentication, authorization, not-found, conflict, internal, and Identity-unavailable responses. The live contract is available at `/v3/api-docs`, with Swagger UI at `/swagger-ui/index.html`.

Request DTOs use Jakarta Bean Validation. List queries document paging defaults/bounds and allow-listed sorting; API success values use `ApiResponse<T>`, while failures use shared `ApiError` and correlation-ID behavior. The machine-readable `api-contracts/rra-roomiq-stage1-contract.json` records the complete resource operation list and API conventions. `OrganizationCrudApiTests` compares the exact OpenAPI method/path set, asserts bearer security, DTO schema, error references/codes, correlation headers, and operation count; the focused suite passed all six tests. The backend parent-reactor Java test run also passed without test failures/errors. No public contract change required an ERD modification.

## S3-08 identifier-only ownership boundary

Organization Service owns its location tables and their hierarchy foreign keys. Identity stores `department_id`, `office_building_id`, and user-role `scope_office_building_id` as UUID identifiers only; it has no foreign keys into Organization, no Organization tables, and no shared Organization JPA entities. The focused Identity repository regression persists identifiers without corresponding Organization records and checks the Identity schema's imported foreign keys. Organization's local geographic foreign keys are unchanged. No ERD change was needed.

## S3-09 isolated verification

The regular Organization test configuration uses an in-memory H2 database with PostgreSQL compatibility mode, Flyway enabled, and Hibernate `ddl-auto=validate`. `OrganizationServiceApplicationTests` asserts those settings and confirms V1-V4 are applied. The original 21 tests across five classes cover migration/schema, domain/persistence, authorization, CRUD/API/OpenAPI, and isolated-context behavior. `OrganizationPostgresMigrationTests` adds four PostgreSQL Testcontainers tests that run Flyway V1-V4 and Hibernate schema validation against PostgreSQL, exercise actual database constraints, and race opposite Department reparent operations to verify serializable cycle prevention. That gate exposed and fixed the `country.iso_code` fixed-width `CHAR(2)` JDBC mapping; no migration or ERD change was required. API coverage includes invalid pagination/sort values returning the shared validation-error envelope. The backend parent-reactor test run also covers Identity and shared `common-web` conventions.

## S3-10 documentation and configuration synchronization

S3-10 synchronized the backend roadmap, Organization service README, this SRS/ERD alignment note, architecture/docs index, API-contract README, and stage JSON. The service/runtime configuration is documented from `organization-service/src/main/resources/application.yml`: `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` supply PostgreSQL connection settings; `IDENTITY_SERVICE_URL` defaults to `http://localhost:8081`; `ORGANIZATION_SERVICE_PORT` defaults to `8082`. The isolated test overrides are documented above and in the stage contract. The SRS and ERD were not modified because no requirement or schema discrepancy was found.

## S3-11 verification gate

The clean Organization test passed 21 tests; the dedicated Identity/common-web reactor passed 35 tests; and the S3-11 clean backend reactor passed 25 suites/69 tests across all 11 reactor projects, with zero failures/errors/skips. No unauthorized ERD or service-boundary changes were made. S3-11 verification is complete and Stage 3 is accepted. Room Service S4-01 through S4-07 are complete and verified, including the Cloudinary photo adapter and Identity-scoped authorization; the current clean backend reactor passed 28 suites/94 tests with zero failures/errors/skips.

## Approved ERD clarification

The original `FLOOR` definition had no timestamp, while S3-01 requires timestamps for all six tables. With explicit approval, `created_at TIMESTAMPTZ NOT NULL` was added to `FLOOR` in the ERD and implemented with a database default in Flyway V1. No other ERD fields were changed.

## Verification and boundary
Current cross-stage note: Room Service S4-09/S4-10 added Identity-delegated authorization before Room writes and Organization reference lookups, plus the documented API contract. S4-11 PostgreSQL-backed verification passed 27 Room/Testcontainers tests and 39 focused Room/client/adapter tests; S4-12 synchronized Room documentation, configuration, Postman examples, and the stage contract. Stage 4 is accepted. Scheduling S5-01 through S5-07 are implemented and verified by PostgreSQL-backed Scheduling, Identity, and full-reactor Java test gates and Stage 5 was accepted by the user. Recurrence uses the existing approved table, caps rules at 365 dates and a one-year inclusive horizon, S5-05 validates candidate dates, and S5-06 uses Room owner APIs and a fail-closed Booking occupancy contract without creating booking occupancy. All Scheduling business routes require bearer/Identity authorization and are not routed through the API Gateway in Stage 5; direct service/OpenAPI network access remains a deployment-perimeter responsibility. No Organization or ERD change was required. Stage 6 S6-01 through S6-05 are complete; Booking uses Organization APIs and may not read its database directly. No Organization implementation or ERD change was needed.

`OrganizationSchemaMigrationTests` runs Flyway against H2 PostgreSQL mode and verifies V1-V4, columns and lengths, geographic foreign keys, direct self-parent rejection, blank and timezone-format checks, all uniqueness scopes, and the ID-only calendar reference. `OrganizationDomainPersistenceTests` verifies JPA schema validation, hierarchy persistence, repository queries, timezone and DTO validation, department reparent cycles/scopes/subtree behavior, and legacy inactive-intermediate protection. `OrganizationCrudApiTests` and `OrganizationAuthorizationInterceptorTests` cover CRUD/status/query behavior, authenticated read/manage delegation, denial envelopes, missing tokens, fail-closed Identity outages, and OpenAPI contract consistency. S3-07 focused verification passed all 6 `OrganizationCrudApiTests`; the backend parent-reactor Java test run passed without failures/errors. Production migrations target PostgreSQL. No ERD modification was needed.