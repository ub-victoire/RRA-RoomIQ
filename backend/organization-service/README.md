# RRA RoomIQ Organization Service

## Current scope

S3-01 through S3-11 verification is complete and Stage 3 is accepted. This service owns the Flyway schema, matching JPA model, hierarchy-integrity and validation rules, versioned CRUD/query APIs, Identity-delegated endpoint enforcement, the complete 38-operation API contract, verified identifier-only references across the Identity boundary, and 25 tests across isolated H2 and PostgreSQL Testcontainers configurations. It documents countries, provinces, districts, office buildings, floors, and departments. Room Service S4-01 through S4-12 documentation and verification is complete and Stage 4 is accepted. Scheduling S5-01 through S5-07 are implemented and verified, and Stage 5 was accepted by the user. S5-06 reads building and department policy through owning-service APIs, and all Scheduling business routes are protected by bearer/Identity authorization but are not routed through the Gateway pending Stage 11. Production deployments must restrict direct service access to a private/trusted network. No Organization or ERD change was required. The Stage 6 implementation preserves this service's ownership boundary: S6-01 through S6-05 are complete, Booking uses Organization APIs and does not read this service's database directly; no Organization change was required.

## Persistence boundary

Flyway migrations V1 through V4 create and constrain the six organization tables with UUID primary keys, timestamps, active/status fields, approved uniqueness scopes, hierarchy foreign keys, a direct-self-parent check, nonblank required name/code/timezone checks, and timezone path-format checks. `office_building.working_calendar_id` remains an identifier only because calendars belong to Scheduling Service. V3/V4 change no ERD fields; the schema retains the approved `floor.created_at` addition.

The persistence model is under `rw.rra.roomiq.organization.domain`: `entity` contains the six entities, `repository` contains the Spring Data repositories, `dto` contains validated create requests, response/page/hierarchy records, and `mapper.OrganizationMapper` translates between DTOs and entities. `DepartmentStatus` maps to the ERD's `ACTIVE`/`INACTIVE` status values. Entity relationships do not cross the Organization Service boundary. Identity's department/building and role building-scope references are UUID identifiers only; it does not share Organization JPA entities or database foreign keys with this service.

The migrations create the hierarchy schema but do not seed country, province, district, building, floor, or department records. The APIs support dynamically managed nationwide data; loading and validating the operational location dataset remains a deployment/data-provisioning dependency and is not implied by S3 implementation completion.

## API Surface

All endpoints use `/api/v1` and the shared `ApiResponse`/`ApiError` conventions:

- `/countries`: CRUD, active status, search/filter/page/sort, and `/{id}/hierarchy` for a single country's Province → District → OfficeBuilding → Floor/Department tree.
- `/provinces`: CRUD and search/filter/page/sort, including `countryId` filtering.
- `/districts`: CRUD and search/filter/page/sort, including `provinceId` filtering.
- `/office-buildings`: CRUD and search/filter/page/sort, including `districtId` filtering.
- `/floors`: CRUD and search/filter/page/sort, including `officeBuildingId` filtering.
- `/departments`: CRUD, status and parent changes, and search/filter/page/sort by building, parent, and status.

List queries default to page 0 and size 20, cap page size at 100, and allow-list sort fields per resource. Deleting a referenced row returns a conflict; records are never cascaded through the hierarchy.

## S3-05 validation and lifecycle

- Required names and codes reject blank values and enforce DTO/column length limits. Database uniqueness follows the ERD: country name and ISO code; province name per country; district name per province; office-building code; floor name per building; department code.
- Country ISO codes are optional; when supplied they must be two ASCII letters and are stored uppercase. Optional blank ISO code clears to null.
- Office-building timezone accepts Java/IANA timezone database IDs only. Null or blank resets to `Africa/Kigali`; surrounding whitespace is trimmed. DTO/service validation checks IANA membership; V4 database validation rejects malformed path syntax. Invalid IDs return `400 INVALID_TIMEZONE` or the shared `VALIDATION_ERROR` envelope at the API boundary.
- An active child requires an active parent. Parent deactivation is rejected with `409 ORGANIZATION_ACTIVE_CHILDREN` while any active descendant remains; reactivation requires the immediate parent to be active. These checks cover country/province/district/building/floor and department status paths.
- Department parent links remain acyclic and within a building scope. Changing a department's building while it has children returns `409 DEPARTMENT_SCOPE_HAS_CHILDREN`; moving a subtree is not implicit. Department parent/scope validation and status transitions are serialized with database row locks.
- On PUT, null `active` preserves its existing value; optional blank text is normalized to null; null/blank timezone resets to the documented default; null `workingCalendarId`, address, ISO code, and department parent clear those optional values. Required fields remain required.
- Updates never cascade or hard-delete history; DELETE remains restricted to unreferenced rows.

All conflict responses use the shared `ApiError` and correlation-ID behavior.

## S3-06 Authorization

Every `/api/v1/**` request is intercepted and delegated to Identity Service using the original bearer token. GET requests require an active identity account. Mutations require the current, database-backed, unscoped `IDENTITY_SYSTEM_ADMIN` permission; scoped Admin permissions do not grant authority to change the location hierarchy. Missing or invalid credentials return 401, policy denial returns 403, and Identity Service unavailability returns 503. Organization Service does not query identity tables or trust client-supplied role/scope data.

Database connection settings use `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD`; Identity Service authorization uses `IDENTITY_SERVICE_URL` (default `http://localhost:8081`). The service listens on `ORGANIZATION_SERVICE_PORT` (default `8082`). The local placeholder values are in `backend/.env.example`. Flyway runs from `classpath:db/migration` at service startup. Tests use isolated H2 databases in PostgreSQL compatibility mode.

## S3-07 API contract and OpenAPI

The live OpenAPI document is `/v3/api-docs` and the Swagger UI is `/swagger-ui/index.html`. Every `/api/v1` operation declares bearer JWT security, the shared `ApiError` schema for errors, and the optional `X-Correlation-ID` request header with the response header documented. Request and response DTOs stay inside Organization Service, requests are Bean-Validated, success responses use `ApiResponse<T>`, and stable error codes are listed in `api-contracts/rra-roomiq-stage1-contract.json`. The contract test asserts all 38 method/path pairs and their success/error schemas, security, and correlation metadata.

## Verification

From `backend/`, run the focused organization tests through the Maven reactor:

```bash
mvn -pl organization-service -am test
```

The 21 H2 Organization tests verify Flyway V1-V4, JPA validation, column lengths and nonblank/unique constraints, geographic foreign-key edges, timezone validation/defaults, active parent/descendant rules, department cycle/scope/subtree checks, nullable update behavior, authorization delegation, pagination/sort bounds, and shared error envelopes. Four additional PostgreSQL Testcontainers tests run the actual Flyway migrations with Hibernate schema validation, verify PostgreSQL constraints, and race opposite Department reparent operations to confirm serializable locking prevents a cycle. The PostgreSQL gate caught and fixed the `CHAR(2)` Hibernate mapping for `country.iso_code`; the schema and API remain unchanged. The migration test container is verification only and does not provision the nationwide operational location dataset.

## S3-10 Documentation synchronization

The Stage 3 checklist and current implementation state are maintained in `backend/Documentation.md`; API operations and test-profile metadata are recorded in `backend/api-contracts/rra-roomiq-stage1-contract.json`; schema traceability is in `backend/docs/organization-srs-erd-alignment.md`. The approved requirements and ERD are unchanged.

## S3-11 Stage 3 verification
This current cross-stage total supersedes the older 93-test full-reactor count retained in the historical Stage 3 verification text below.
Current cross-stage status: S4-11 Room/PostgreSQL verification passed 27 Room/Testcontainers tests and 39 focused Room/client/adapter tests; S4-12 synchronized Room docs, runtime settings, and the 31-operation Postman collection. Identity/common-web passed 41 tests and the full reactor passed 28 suites/107 tests with zero failures/errors/skips. No Organization or ERD change was required; Stage 4 awaits explicit user acceptance.

The clean Organization test passed 21 tests; the dedicated Identity/common-web reactor passed 35 tests; and the S3-11 clean full backend reactor passed 25 suites and 69 tests across all 11 Maven reactor projects. All runs completed with zero failures/errors/skips. No unauthorized ERD or architecture changes were made. Stage 3 is accepted. Room Service S4-01 through S4-07 verification passed; the current clean full backend reactor passed 28 suites/93 tests with zero failures/errors/skips.