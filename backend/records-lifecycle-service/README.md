# Records Lifecycle Control Service

RLC owns retention-policy lifecycle management and eligibility evaluation. It does not execute disposal, archive transfers, or legal-hold workflows.

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

The migration intentionally fails if either role has not been provisioned. Migrations do not create production accounts or credentials. Existing installations must have the DBA verify/transfer object ownership to the migration principal before applying V5. The database owner and superusers remain privileged and can alter triggers; deployment evidence must record and control that residual privilege.

## Container

Build from the backend directory with:

```sh
docker build -f records-lifecycle-service/Dockerfile -t roomiq-records-lifecycle-service .
```

The service can be enabled in the local infrastructure Compose file with the `applications` profile. The Compose deployment requires the same environment variables listed above; do not put production secrets in the Compose file or repository. Database and Identity hostnames, TLS termination, secret delivery, database roles and operational monitoring must be provisioned by RRA operations.
