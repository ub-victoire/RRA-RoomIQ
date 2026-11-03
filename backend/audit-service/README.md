# Audit Service

Audit Service currently contains a narrow Notification template-audit consumer and `AUDIT_EVENT` persistence path. It validates three Notification template event types and deduplicates persisted IDs. Identity's transactional audit outbox exists, but Audit Service currently has no Identity queue/listener. There is no Audit search REST API, no implemented Identity `AUDIT_READ` permission, no verified append-only/tamper-resistance mechanism, and no retention processing. These are implementation gaps, not completed Stage 8 requirements.

The approved Stage 8 checklist and status table are in [Backend Documentation](../Documentation.md#stage-8-requirement-checklist-approved). S8-01 was separately authorized and its documentation-only [Audit Action-to-Owner Matrix](../docs/audit-action-owner-matrix.md) is implemented and verified, pending user acceptance. It identifies producer evidence and gaps; it does not connect missing producers or add event contracts. S8-02 through S8-05 and S8-07 still require explicit item authorization before implementation. The approved search policy requires the dedicated `AUDIT_READ` permission for global Super Admins and building-scoped Admins; scoped Admins are restricted to their current assigned buildings. S8-06 remains blocked until RRA approves retention and archival/disposal policy. No Stage 8 application API, ERD change, or migration was approved or changed.

## Notification template audit consumer

Notification persists a safe audit event to its own transactional outbox with the template mutation. The Notification relay publishes persistent messages on the `roomiq.events.v1` exchange with routing key `audit.notification`, and marks an outbox row published only after a positive RabbitMQ publisher confirmation and successful routing. Audit Service consumes the durable `roomiq.audit.notification.v1` queue. It validates the exact envelope, schema version, event type, source, resource, and metadata fields before persistence.

`AUDIT_EVENT.id` is the event ID, making exact duplicate delivery idempotent. Conflicting reuse of an event ID is rejected. The processor is transactional and the listener returns after processing; the listener is configured for automatic acknowledgement and bounded retry, with invalid/unsupported messages routed to the durable `roomiq.audit.notification.dlx.v1` exchange and `roomiq.audit.notification.dead-letter.v1` queue. The current implementation does not prove append-only or database-level tamper resistance: the migration has no trigger/immutability control, and the repository inherits JPA mutation operations. Do not describe the store as immutable until an enforcement mechanism is selected and tested. Audit stores actor IDs as identifiers only and never reads the Notification database. Template subject/body content is intentionally excluded from audit metadata.

## Local configuration

Configure PostgreSQL and RabbitMQ outside source control:

```dotenv
AUDIT_DB_URL=jdbc:postgresql://localhost:5432/roomiq_audit
AUDIT_DB_USERNAME=roomiq
AUDIT_DB_PASSWORD=replace-with-local-secret
RABBITMQ_HOST=localhost
RABBITMQ_PORT=5672
AUDIT_RABBITMQ_USERNAME=replace-with-audit-account
AUDIT_RABBITMQ_PASSWORD=replace-with-local-secret
AUDIT_EVENT_MAX_ATTEMPTS=3
AUDIT_EVENT_RETRY_INITIAL_INTERVAL=1s
AUDIT_EVENT_RETRY_MULTIPLIER=2
AUDIT_EVENT_RETRY_MAX_INTERVAL=5s
```

Provide a service-specific broker account restricted to the required exchange, queue, and dead-letter topology in deployed environments. The local shared/guest account defaults are for development only; never commit real credentials.

## Verification

From the repository root:

```bash
mvn -f backend/pom.xml -pl audit-service -am test
```

The prior Stage 7 Notification template-audit integration evidence is recorded in [Backend Documentation](../Documentation.md#stage-7-requirement-checklist-approved). It is not Stage 8 acceptance evidence. The Stage 8 item-by-item scope and gates are in [Backend Documentation](../Documentation.md#stage-8-requirement-checklist-approved).
