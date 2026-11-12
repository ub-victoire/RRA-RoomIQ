# Records Lifecycle Control Service

RLC implements a policy-version/rule registry and policy lifecycle management APIs, transactional lifecycle-event evidence, and a non-destructive retention eligibility calculation. It does not obtain trusted record facts from source owners and does not execute disposal, archive transfers, or legal-hold workflows. Eligibility is not disposal authorization.

## Current implementation and limits

The policy API supports draft create/get, rule create/update, validation, activation, supersession, and withdrawal. Policy writes and corresponding lifecycle-control events share a database transaction; database guards require matching lifecycle evidence for policy mutations, and lifecycle-control events are append-only for the runtime role. Policy-management requests use bearer authentication and fail-closed authorization through Identity. Identity requires an active global Super Admin with the explicit `RETENTION_POLICY_MANAGE` permission; the permission is not automatically granted.

The calculator selects the effective policy for the supplied authoritative start time, applies matching rules for one supplied category, uses the longest expiry among those rules, and treats expiry equality as eligible. It fails closed for missing or conflicting policies, missing category/start, unknown or unacknowledged owner, missing statutory schedule results, and unavailable or blocking hold state. Inputs are supplied to the evaluator; there is no source-owner adapter, category classifier, operational hold registry, or cross-category longest-applicable calculation. No calculation result authorizes archive or disposal.

The retention schedule remains proposed. Formal RRA Legal/Records Management validation and management approval are not recorded. The S8-06A register has 36 open decisions with blank approval fields. Documentation cites a CTO S8-06C implementation-baseline instruction, but no signed instruction is attached to the repository; an implementation baseline is not formal policy approval, technical readiness, or operational readiness. HOLD-01 through HOLD-05 remain unresolved. S8-06D and S8-06F have no authoritative definitions in the repository. ARC-08 and DSP-06 criteria have no corresponding approval-package rows.

No source-owner lifecycle integrations, archive transfer/verification/recovery, operational legal-hold enforcement, or disposal clearance/authorization/execution/certificates/reconciliation are implemented. Audit remains append-only and no Audit retention/disposal mechanism exists. S8-06 remains blocked; S8-07 and Stage 8 acceptance remain blocked.

The available 2026-11-05 Surefire reports show 4 RLC suites / 40 tests with zero failures, errors, or skips. They include PostgreSQL/Testcontainers policy schema, runtime/migration-role, event/write guard, transactional rollback, Identity authorization denial/unavailability, idempotency, activation/supersession/history and calculator tests. Identity is mocked in the RLC PostgreSQL test. These are historical module tests, not live RLC-to-Identity or source-owner E2E evidence, and do not test archive, operational holds, or disposal.

## Runtime configuration

The service requires the following externally provisioned settings:

| Variable | Purpose |
|---|---|
| `RLC_DB_URL` | PostgreSQL JDBC URL for the RLC database |
| `RLC_DB_USERNAME` / `RLC_DB_PASSWORD` | Runtime principal `roomiq_rlc_app` |
| `RLC_DB_MIGRATION_USERNAME` / `RLC_DB_MIGRATION_PASSWORD` | Flyway principal `roomiq_rlc_migrator` |
| `IDENTITY_SERVICE_URL` | Base URL for the Identity authorization API |
| `JWT_SECRET` | Shared HS256 signing/verification key; at least 32 bytes |

`IDENTITY_CONNECT_TIMEOUT` and `IDENTITY_READ_TIMEOUT` default to 2 and 5 seconds. `RLC_SERVICE_PORT` defaults to 8089. Production (`prod` or `production` Spring profile) requires an HTTPS Identity URL. Identity authorization is fail-closed: a denial returns 403, while an unavailable Identity service returns 503 and does not permit policy writes.

Liveness and readiness are exposed at `/livez` and `/readyz`; readiness includes the application readiness state and PostgreSQL connectivity. Identity is not probed without an authenticated request, so the readiness endpoint does not assert that Identity authorization is currently reachable.

## Database provisioning

Before Flyway starts, the DBA must create separate, non-superuser principals named `roomiq_rlc_migrator` and `roomiq_rlc_app`, provision their credentials through the approved secret manager, and grant the migrator the required schema migration privileges. The migrator must own the RLC tables/functions; the runtime principal must not own them and must not inherit owner, migration, or superuser privileges. Flyway V5 grants the runtime principal SELECT/INSERT on lifecycle events and SELECT/INSERT/UPDATE on policy tables. It grants no event UPDATE/DELETE/TRUNCATE or policy DELETE/TRUNCATE privileges; policy mutation triggers still require a matching lifecycle event in the same transaction.

The migration intentionally fails if either role has not been provisioned. Migrations do not create production accounts or credentials. Existing installations must have the DBA verify/transfer object ownership to the migration principal before applying V5. The database owner and superusers remain privileged and can alter triggers; deployment evidence must record and control that residual privilege. Passing Testcontainers role/trigger tests does not establish production provisioning.

## Container

Build from the backend directory with:

```sh
docker build -f records-lifecycle-service/Dockerfile -t roomiq-records-lifecycle-service .
```

The service can be enabled in the local infrastructure Compose file with the `applications` profile. The Compose deployment requires the same environment variables listed above; do not put production secrets in the Compose file or repository. Database and Identity hostnames, TLS termination, secret delivery, database roles and operational monitoring must be provisioned by RRA operations.
