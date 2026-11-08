- S4-11/S4-12 verification confirms Room lifecycle, authorization, API/error contracts, Cloudinary failure compensation, and synchronized Room API examples use the shared `ApiResponse<T>`, `ApiError`, Bean Validation, and correlation-ID conventions. Stage 4 is accepted. S5-01 Scheduling migrations and persistence preserve the shared contracts; Identity/common-web passed 41 tests and the full reactor passed 28 suites/111 tests with zero failures/errors/skips.
- PostgreSQL Testcontainers verify Room migrations, repositories, constraints, and maintenance exclusion behavior; common-web and Identity remain green in the same reactor run.
# RRA RoomIQ Common Web

Shared HTTP conventions consumed by every backend service.

## Standards

- `ApiResponse<T>` is the success response envelope.
- `ApiError` is the error response DTO.
- `DomainException` carries a stable machine-readable code, HTTP status, and safe message for domain failures.
- `GlobalExceptionHandler` maps domain, validation, security, and unexpected errors to the shared response contract without exposing exception messages or stack traces.
- `CorrelationIdFilter` validates, accepts, or creates `X-Correlation-ID` and propagates the same value through request state, MDC, and response headers.
- `RoomIqWebConfiguration` supplies OpenAPI defaults and registers shared web components.
- `logback-spring.xml` defines the shared console format with correlation IDs.
- Room Service S4-11/S4-12 and Scheduling S5-02/S5-03 APIs use the shared `ApiResponse<T>`, `ApiError`, Bean Validation, and correlation-ID conventions. Scheduling calendar, window, holiday, and closure operations also document bearer security and stable validation, authorization, conflict, and dependency errors. The Scheduling and full-reactor Java test gates passed.

Each service adds this module as a Maven dependency and may extend the conventions with domain-specific controllers and DTOs.

## Stage 2 verification

The identity-service repository tests consume these shared conventions and run successfully through the backend Maven reactor.

Identity DTO validation tests also verify that constraint failures use the shared `ApiError` and `ValidationError` response contract.

S2-14 tests verify stable domain error mapping and correlation ID consistency for valid and invalid caller-supplied IDs.

S2-15 identity integration tests consume these conventions across authentication, authorization, controller, migration, and audit flows. The clean identity/common-web suite passed 12 suites and 37 tests; the complete backend reactor passed 20 suites and 45 tests.

S2-16 documentation verifies the executable Postman collection uses the same shared error, bearer-authentication, and correlation-ID conventions.

S2-17 traceability and ownership notes are maintained in [identity-srs-erd-alignment.md](../docs/identity-srs-erd-alignment.md); common-web remains a shared HTTP convention module and owns no identity or audit persistence.

S2-18 verification passed the complete backend reactor without regressions: 20 suites and 45 tests, zero failures/errors.

S3-04 through S3-11 Organization endpoints use the shared `ApiResponse<T>` and `ApiError` handling, Bean Validation, correlation-ID filter, and OpenAPI contract. Organization MockMvc tests verify CRUD, validation, not-found, referenced-delete conflicts, lifecycle/update behavior, pagination/sort validation, Identity-delegated authorization, fail-closed errors, bearer documentation, and correlation/error contract coverage. The clean Organization test, Identity/common-web test, and full-reactor gates passed.
