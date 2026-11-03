# Notification Service

Notification Service owns notification persistence and event consumption. It does not read or write another service's database and provides the authenticated in-app inbox workflow for a user’s own notifications.

## S7-03 inbox API

The inbox requires an Identity-issued bearer token. For every list or read request, Notification forwards that token to Identity's existing protected `POST /api/v1/internal/authorization/booking` `AUTHENTICATE` action. Identity validates the session and active account and returns the authoritative user ID; Notification does not trust a caller-supplied recipient ID or access Identity's database. Identity failures fail closed.

`GET /api/v1/notifications` (also available at `/api/v1/me/notifications`, `/api/v1/inbox`, and `/api/v1/me/inbox`) returns a paginated list filtered to that Identity user. Optional filters are `eventType`, `status`, and `read`; pagination defaults to `page=0,size=20` and accepts sizes from 1 to 100. Sorting supports `createdAt`, `readAt`, `eventType`, `status`, or `id`, with `sortDirection=asc|desc`. Invalid filters, pagination, or sort values return the shared error envelope. `PATCH /api/v1/notifications/{notificationId}/read` (also available under `/api/v1/me/notifications/...`, `/api/v1/inbox/...`, and `/api/v1/me/inbox/...`) atomically sets `readAt` only when the notification belongs to the authenticated user. Repeated reads preserve the first read timestamp; another user's or nonexistent notification returns the same not-found error.

Inbox responses use the shared `ApiResponse` success envelope and contain the notification state/timestamps, event and resource references, and a display-payload allowlist. Payload parsing never falls back to returning raw stored content. User identity/contact fields and unapproved event payload fields are omitted. Existing `NOTIFICATION.read_at`, event/resource fields, payload, and recipient-created index satisfy the approved ERD; no migration or ERD change was made.

## S7-04 channel preferences

`GET /api/v1/me/notification-preferences` returns effective preferences for every supported event type. `PUT /api/v1/me/notification-preferences/{eventType}` replaces that user's two required flags. Both routes forward the caller bearer token to Identity's protected `AUTHENTICATE` action and derive ownership exclusively from the active Identity account; request bodies and paths cannot choose another user. Responses use the shared `ApiResponse` envelope, and errors use `ApiError`.

The approved `USER_NOTIFICATION_PREFERENCE` entity is implemented by Flyway V3 with only `id`, `user_id`, `event_type`, `in_app_enabled`, `email_enabled`, and the unique `(user_id,event_type)` constraint. The `user_id` is an Identity-owned identifier; Notification does not access the Identity database or create a cross-service foreign key. PostgreSQL upsert makes repeated same-payload PUT requests safe. Unsupported event types and missing flags are rejected.

If no row exists, effective defaults are in-app enabled and email disabled. Stored channel flags are independent, and there are no non-disableable event classes. In-app event creation applies the in-app flag; scheduled reminders recheck it at delivery time and are cancelled if it has since been disabled. Email preferences independently gate durable EMAIL notification creation. Sending remains disabled by default until the approved relay and sender values are supplied.

## S7-05 templates and administration

Templates are immutable versions unique by `(code, version, channel)`; a new version is inactive. `GET /api/v1/internal/notification-templates`, `POST /api/v1/internal/notification-templates`, and `PATCH /api/v1/internal/notification-templates/{templateId}/activation` require an Identity-authorized active global Super Admin with the explicit `NOTIFICATION_TEMPLATE_MANAGE` permission. Notification forwards the caller bearer token to Identity's protected template-authorization action for every operation. Create validates the template code/version/channel and body syntax; email templates also require a subject. Each mutation returns a shared response envelope.

For in-app processing, the implemented code convention is `template.code = event.eventType`. Bodies use `{{variableName}}` placeholders resolved only from the event's allowlisted safe display facts. Body substitutions are HTML-escaped, subject substitutions have newlines removed, and malformed, missing, or incompatible templates fail explicitly rather than using fallback content. Each code/channel has at most one active version: activation serializes on a PostgreSQL transaction advisory lock and switches versions atomically. S7-06 renders active EMAIL versions into durable delivery rows when a recipient's email preference is enabled.

Create, activation, and deactivation persist a safe audit event to Notification's approved transactional `notification_audit_outbox` in the same transaction. The payload records only template code/version/channel and changed field names, never subject/body content. A scheduled relay sends persistent events on `audit.notification` with the broker message ID set to the event ID, waits for broker confirmation and routing success, and retries failed sends with bounded exponential delay. Audit Service requires the matching broker message ID, validates the event (including nullable correlation using the shared Identity format), retains source/schema version alongside the existing safe event metadata, deduplicates it in its own `AUDIT_EVENT` table, and dead-letters invalid or unsupported events. The original producer timestamp and trusted actor/resource facts are preserved; the template event has null building scope and `SUCCESS` outcome. Neither service reads the other's database. The Notification template APIs are not exposed through the API Gateway.

## S7-06 asynchronous email delivery

Email-enabled events and reminders create durable `EMAIL` notification rows using the selected active `EMAIL` template and recipient preference. The scheduled worker claims pending email rows using PostgreSQL `FOR UPDATE SKIP LOCKED`, resolves the active recipient's stored address through Identity's hidden `POST /api/v1/internal/notifications/delivery-contact`, then sends via the vendor-neutral Spring SMTP adapter. The Identity call uses a short-lived RS256 token with only `notification:delivery-contact-resolution`; Identity returns only the email stored for the active account. Notification stores recipient identifiers, not email addresses, and no caller/event display or contact override is trusted. The endpoint is not exposed through the API Gateway, and Notification does not access another service database.

Flyway V5 adds `delivery_attempt` with the already-approved ERD fields. Each attempt stores the attempt number, result, timestamp, optional provider message ID, and a generic redacted failure code. Successful sends move an EMAIL notification to `SENT`; retryable failures remain `SCHEDULED` until due, and permanent/exhausted failures move to `DEAD_LETTER`. Raw provider exceptions, credentials, recipient addresses, and message content are not recorded in failure details. The generic SMTP protocol has no standard provider message ID; this remains null when the relay does not provide one. External SMTP delivery is asynchronous and is never part of Booking/Room/Scheduling transactions. Exactly-once delivery is not claimed for SMTP side effects.

`NOTIFICATION_EMAIL_ENABLED` defaults to `false` and must remain disabled until RRA provides the approved SMTP-compatible relay and organization-controlled sender. Configuration is injected through deployment environment or secret storage; do not commit secrets. Set `NOTIFICATION_SMTP_HOST`, `NOTIFICATION_SMTP_PORT`, `NOTIFICATION_SMTP_USERNAME`, `NOTIFICATION_SMTP_PASSWORD`, `NOTIFICATION_SMTP_AUTH`, `NOTIFICATION_SMTP_TLS_MODE`, `NOTIFICATION_SMTP_STARTTLS_ENABLED`, `NOTIFICATION_SMTP_STARTTLS_REQUIRED`, `NOTIFICATION_SMTP_IMPLICIT_TLS`, `NOTIFICATION_SMTP_CONNECTION_TIMEOUT_MS`, `NOTIFICATION_SMTP_READ_TIMEOUT_MS`, `NOTIFICATION_SMTP_WRITE_TIMEOUT_MS`, `NOTIFICATION_EMAIL_SENDER_ADDRESS`, and `NOTIFICATION_EMAIL_SENDER_NAME`. STARTTLS is required by default; implicit TLS requires the matching TLS flags. `NOTIFICATION_EMAIL_FIXED_DELAY_MS` and `NOTIFICATION_EMAIL_BATCH_SIZE` configure worker cadence and batch limit. SMTP host, credentials, sender address, and formally approved sender name are intentionally unset.

## S7-07 retry, dead-letter, redrive, and operations

Email delivery permits five total attempts (initial attempt plus four retries) with delays of 1, 2, 4, and 8 minutes. SMTP 4xx responses and temporary network/provider errors retry. SMTP 5xx responses, invalid recipients, and invalid sender/configuration are permanent unless an explicitly configured provider-documented temporary response code applies. `NOTIFICATION_EMAIL_RETRY_MAXIMUM_ATTEMPTS`, `NOTIFICATION_EMAIL_RETRY_INITIAL_DELAY_MS`, `NOTIFICATION_EMAIL_RETRY_MULTIPLIER`, and `NOTIFICATION_EMAIL_RETRY_MAXIMUM_DELAY_MS` configure the bounded policy; `NOTIFICATION_SMTP_RETRYABLE_RESPONSE_CODES` optionally lists provider-specific retryable 5xx codes. Retry-waiting EMAIL rows use the existing `SCHEDULED` state and `scheduled_at`. Permanent and exhausted failures become `DEAD_LETTER`; each attempt's outcome and sanitized failure code remain inspectable. In-app notification state is independent.

The user-approved `NOTIFICATION_DELIVERY_REDRIVE` table is implemented by Notification Flyway V6 with the exact approved schema: notification ID, idempotency key (128 characters maximum), actor user ID without a cross-service FK, requested timestamp, and unique `(notification_id,idempotency_key)`. Redrive is allowed only for a currently dead-lettered EMAIL row, reuses that notification and schedules a fresh bounded attempt cycle. Repeating the same notification/key is idempotent; another notification is never created. The operator must supply an `Idempotency-Key`.

The bearer-protected internal API provides aggregate `GET /api/v1/internal/notification-delivery/health`, paginated `GET /api/v1/internal/notification-delivery/dead-letters`, sanitized `GET /api/v1/internal/notification-delivery/dead-letters/{notificationId}`, and `POST /api/v1/internal/notification-delivery/dead-letters/{notificationId}/redrive`. Identity authorizes every operation; only an active global Super Admin with the explicit `NOTIFICATION_DELIVERY_MANAGE` permission is allowed. Identity Flyway V4 catalogs this permission but does not assign it. Health contains aggregate queue depth, oldest queued creation time, retries, dead-letter count, delivery success/failure, and latency count/average/maximum; it excludes content, recipient IDs, and personal data. Queue/outcome aggregates are database-backed, while latency timer samples are process-local. Dead-letter details expose sanitized attempt and redrive history without recipients or message payload. These routes are not API Gateway-routed.

### S7-07 verification

| Command | Result |
|---|---|
| `mvn -f backend/pom.xml -pl notification-service -am -Dtest=NotificationEventProcessingPostgresTests,NotificationDeliveryOperationsControllerTests,NotificationEmailContactClientTests,NotificationEmailFailureClassifierTests,NotificationEmailDeliveryServiceTests -Dsurefire.failIfNoSpecifiedTests=false test` | 45 focused Notification tests passed; common-web dependency 6/6; zero failures, errors, or skips |
| `mvn -f backend/pom.xml -pl notification-service -am test` | Notification module 98/98; common-web 6/6; zero failures, errors, or skips |
| `mvn -f backend/pom.xml -pl identity-service -am test` | Identity module 67/67; common-web 6/6; zero failures, errors, or skips |
| `mvn -f backend/pom.xml -pl common-web test` | 6/6; zero failures, errors, or skips |
| `mvn -f backend/pom.xml test` | All 11 Maven modules and 448 tests passed; zero failures, errors, or skips |

## S7-08 integration gate

The final integration regression adds `NotificationEventListenerRabbitPostgresTests.secretaryRequestThenAdminApprovalRoutesNotificationsToScopedAdminAndRequesterOnce`. It publishes a version-2 Secretary `BookingRequested` with explicit scoped-Admin routing, then a `BookingApproved` decision. The real listener consumes both persistent messages into Testcontainers RabbitMQ and PostgreSQL; the test verifies the scoped Admin receives the request notice, the requester receives the decision and applicable comment, correlation/causation are preserved, and replaying both events does not duplicate receipts or notifications. The Identity recipient-client boundary is stubbed for this combined Notification flow; its HTTP contract, while Identity's actual scope/permission behavior, are covered in separate client and Identity tests. Booking's PostgreSQL tests independently verify its transactional outbox events and routing facts.

The rest of the S7-08 suite covers PostgreSQL migrations/repositories/domain transactions, channel preference isolation, owner-scoped inbox access, template version/rendering/audit behavior, SMTP success and sanitized failures, retry exhaustion, dead-letter history/redrive, protected operations/OpenAPI, duplicate/concurrent delivery, terminal-before-reminder ordering, and malformed/unsupported-event dead-lettering. No S7-08 schema or API Gateway route was introduced. The exact focused, Notification, Identity, common-web, and full-reactor commands/results are recorded in the [S7-08 verification gate](../Documentation.md#stage-7-requirement-checklist-approved) and [runtime validation report](../docs/runtime-validation-report.md). All S7-01 through S7-08 requirements have passed individual verification; Stage 7 was accepted by the user.

## S7-02 recipient routing

Booking request and extension-request notifications are routed from version-2 event facts, not an event-supplied recipient list. `BookingRequested` with `notificationRoute=SCOPED_ADMIN_REQUEST` and `ExtensionRequested` with the same route invoke Identity's hidden `POST /api/v1/internal/notifications/recipient-resolution` endpoint. Identity requires a short-lived RS256 service JWT for subject `notification-service`, the recipient-resolution audience and permission, validates the Booking event context, checks the requester's current active Secretary role and scoped permission, derives the building from Identity-owned profile data, and returns only active Admin user IDs assigned to that building. Direct booking has `notificationRoute=NONE`; legacy version-1 request events produce a durable receipt but no guessed recipients. Event correlation IDs are forwarded as `X-Correlation-ID`; durable Notification receipts retain both correlation and causation IDs.

Approval, rejection, and extension-decision events notify only their service-owned requester/Secretary recipient and preserve an applicable decision comment in the safe display payload. Cancellation and room-status/maintenance recipients are service-owned user identifiers from Booking/Room event production; event payloads cannot override contact addresses or display names. Reminder events retain the previously approved organizer-only audience. `MeetingCompleted`, `MeetingReleased`, and `AutomaticReleaseOrNoShow` events are durably receipted and deduplicated but create no notification rows until their recipient configuration is explicitly approved. Terminal events may cancel already-scheduled reminders without sending a new notice. Notification calls Identity over HTTP and never accesses another service's database. No Notification route is added to the API Gateway.

## S7-01 event consumer

The service consumes persistent messages from the durable `roomiq.events.v1` direct exchange and its `roomiq.notifications.events.v1` queue. It binds only the documented S7-01 routing keys. A durable dead-letter exchange and queue retain malformed, unsupported, mismatched-source/routing-key, and retry-exhausted messages. The main listener uses automatic acknowledgement and finite exponential retries (three attempts by default); the transactional processor returns only after PostgreSQL commits, so the broker acknowledges only committed work.

The JSON event envelope contains `eventId`, `sourceService`, `eventType`, `eventVersion`, `aggregateType`, `aggregateId`, `aggregateVersion`, `occurredAt`, `payload`, and optional `correlationId`/`causationId`. Version 1 supports the documented families; version 2 is accepted only for `BookingRequested` and `ExtensionRequested` with explicit routing facts. Event type/source pairing, aggregate type, routing key, required event facts, UUIDs, timestamps, correlation fields, and recipient identifiers are validated. Payloads must not provide email addresses or display-name overrides; recipients remain identifiers and are resolved from service-owned data. `sourceService` is a body claim, not authentication. Production must use separate broker credentials and publisher routing-key ACLs so only each owning service can publish its event families; this broker authorization policy is not configured or integration-tested in this repository yet.

Every valid event and its resulting in-app rows are persisted in one transaction. `notification_event_receipt.event_id` is unique and inserted with PostgreSQL `ON CONFLICT DO NOTHING` before effects are saved. A matching replay is a no-op; reusing an event ID with conflicting envelope metadata is rejected. An event may yield zero or multiple recipients, and an effect persistence failure rolls back the receipt and all rows together.

### Local configuration

Configure the service database and broker outside source control:

```dotenv
NOTIFICATION_DB_URL=jdbc:postgresql://localhost:5432/roomiq_notification
NOTIFICATION_DB_USERNAME=roomiq
NOTIFICATION_DB_PASSWORD=replace-with-local-secret
RABBITMQ_HOST=localhost
RABBITMQ_PORT=5672
RABBITMQ_USERNAME=replace-with-service-account
RABBITMQ_PASSWORD=replace-with-local-secret
NOTIFICATION_EVENT_CONSUMER_ENABLED=true
NOTIFICATION_EVENT_MAX_ATTEMPTS=3
NOTIFICATION_EVENT_RETRY_INITIAL_INTERVAL=1s
NOTIFICATION_EVENT_RETRY_MULTIPLIER=2
NOTIFICATION_EVENT_RETRY_MAX_INTERVAL=5s
NOTIFICATION_REMINDER_DELIVERY_ENABLED=true
NOTIFICATION_REMINDER_FIXED_DELAY_MS=1000
NOTIFICATION_EMAIL_ENABLED=false
# Keep email disabled until RRA approves the relay and organization-controlled sender.
NOTIFICATION_SMTP_HOST=
NOTIFICATION_SMTP_PORT=
NOTIFICATION_SMTP_USERNAME=
NOTIFICATION_SMTP_PASSWORD=
NOTIFICATION_SMTP_AUTH=false
NOTIFICATION_SMTP_TLS_MODE=STARTTLS
NOTIFICATION_SMTP_STARTTLS_ENABLED=true
NOTIFICATION_SMTP_STARTTLS_REQUIRED=true
NOTIFICATION_SMTP_IMPLICIT_TLS=false
NOTIFICATION_SMTP_CONNECTION_TIMEOUT_MS=3000
NOTIFICATION_SMTP_READ_TIMEOUT_MS=5000
NOTIFICATION_SMTP_WRITE_TIMEOUT_MS=5000
NOTIFICATION_EMAIL_SENDER_ADDRESS=
NOTIFICATION_EMAIL_SENDER_NAME=
NOTIFICATION_EMAIL_FIXED_DELAY_MS=1000
NOTIFICATION_EMAIL_BATCH_SIZE=25
NOTIFICATION_EMAIL_RETRY_MAXIMUM_ATTEMPTS=5
NOTIFICATION_EMAIL_RETRY_INITIAL_DELAY_MS=60000
NOTIFICATION_EMAIL_RETRY_MULTIPLIER=2
NOTIFICATION_EMAIL_RETRY_MAXIMUM_DELAY_MS=480000
# Optional, only for provider-documented temporary SMTP 5xx responses.
NOTIFICATION_SMTP_RETRYABLE_RESPONSE_CODES=
NOTIFICATION_AUDIT_RELAY_ENABLED=true
NOTIFICATION_AUDIT_RELAY_DELAY_MS=1000
NOTIFICATION_AUDIT_RELAY_CONFIRM_TIMEOUT_MS=3000
# Same issuer/audience/key ID as Identity; mount the private key from a secret.
ROOMIQ_SERVICE_TOKEN_ISSUER=https://roomiq.local/identity
NOTIFICATION_IDENTITY_RECIPIENT_AUDIENCE=rra-roomiq-identity-recipient-resolution
NOTIFICATION_SERVICE_TOKEN_KEY_ID=notification-current
NOTIFICATION_SERVICE_TOKEN_PRIVATE_KEY_LOCATION=file:/run/secrets/notification-service-private.pem
# Optional service-specific broker account; shared RABBITMQ_USERNAME/PASSWORD
# are development defaults only.
NOTIFICATION_RABBITMQ_USERNAME=replace-with-notification-account
NOTIFICATION_RABBITMQ_PASSWORD=replace-with-local-secret
# Audit Service consumes the audit.notification routing key using its own account.
AUDIT_DB_URL=jdbc:postgresql://localhost:5432/roomiq_audit
AUDIT_DB_USERNAME=roomiq_audit_app
AUDIT_DB_PASSWORD=replace-with-audit-runtime-secret
AUDIT_RABBITMQ_USERNAME=replace-with-audit-account
AUDIT_RABBITMQ_PASSWORD=replace-with-local-secret
```

Audit Service's separate Flyway migrator credentials are configured only in the Audit Service deployment; do not provide them to Notification Service.

Provide RabbitMQ credentials with publish/consume access limited to the required exchanges and queues in deployed environments. The local `guest` default is for development only. Do not add credentials to tracked configuration files.

## Verification

From `backend/`:

```bash
mvn -pl notification-service -am test
mvn -pl identity-service -am test
mvn -pl common-web test
mvn -pl audit-service -am test
mvn test
```

The S7-01/S7-02 PostgreSQL and RabbitMQ integration tests use Testcontainers and require a running Docker daemon. S7-02 verification passed: focused Notification routing/transaction tests 20/20, complete Notification suite 39/39, Identity 62/62, common-web 6/6, Booking focused route-facts test 5/5, and the complete 10-module backend reactor 379/379. All had zero failures, errors, or skips. The first reactor run exposed an outdated Booking unit-test expectation for the v2 event overload; the assertion was updated and the full reactor rerun passed. See the central backend roadmap and `stage7Progress` contract for the verified policy and evidence.

S7-03 verification passed: `mvn -pl notification-service -am -Dtest=NotificationInboxServiceTests,NotificationIdentityAuthenticationClientTests,NotificationEventProcessingPostgresTests -Dsurefire.failIfNoSpecifiedTests=false test` ran 22 focused Notification tests; `mvn -pl notification-service -am test` passed 45 Notification tests; `mvn -pl identity-service -am test` passed 62 Identity tests; `mvn -pl common-web test` passed 6 tests; and `mvn test` passed all 385 tests across 10 backend modules. Every run had zero failures, errors, or skips. No inbox migration or ERD change was required.

S7-04 verification passed: `mvn -pl notification-service -am -Dtest=NotificationEventProcessingPostgresTests,NotificationInboxServiceTests,NotificationIdentityAuthenticationClientTests,NotificationEventProcessingRoutingTests -Dsurefire.failIfNoSpecifiedTests=false test` ran 36 focused Notification tests; `mvn -pl notification-service -am test` passed 54 Notification tests; `mvn -pl identity-service -am test` passed 62 Identity tests; `mvn -pl common-web test` passed 6 tests; and `mvn test` passed all 394 tests across 10 backend modules. Every run had zero failures, errors, or skips. Flyway V3 implements the approved preference entity without changing the ERD.

S7-05 verification passed: focused template API/renderer/outbox/event tests passed 51/51; the RabbitMQ/PostgreSQL listener tests passed 5/5 after seeding active templates in the test fixture; the complete Notification Service suite passed 69/69; Audit Service passed 6/6; Identity passed 63/63; common-web passed 6/6; and `mvn -f backend/pom.xml test` passed all 415 tests across 11 modules. Every final run had zero failures, errors, or skips. The first complete Notification run had four failures because existing broker tests had no active templates; the fixtures were corrected and both the focused listener suite and complete suite then passed. The approved ERD audit-outbox addition, Flyway migrations, contracts, and service documentation are synchronized. See the [S7-05 verification gate](../Documentation.md#stage-7-requirement-checklist-approved).

S7-06 verification passed: SMTP delivery/configuration tests passed 7/7, PostgreSQL notification persistence/migration tests passed 29/29, routing/service-token tests passed 10/10, the complete Notification suite passed 78/78, Identity passed 66/66, common-web passed 6/6, and the full 11-module reactor passed 427/427. Each command reported zero failures, errors, and skips; the full reactor ran PostgreSQL and RabbitMQ Testcontainers tests. The final focused and reactor runs include fail-fast validation and tests for positive SMTP connection/read/write timeouts. Exact commands and results are recorded in the [S7-06 verification gate](../Documentation.md#stage-7-requirement-checklist-approved). Production SMTP remains disabled until RRA supplies the approved relay and sender configuration. S7-07 then adds bounded asynchronous retry and dead-letter handling; the current final regression results are documented in the [S7-07 verification gate](../Documentation.md#stage-7-requirement-checklist-approved).

## Event producer status

Booking and Room publish notification business events after source commits through their local transactional outboxes. Booking emits request/decision/cancellation/extension/completion events, emits `MeetingReleased` only from the protected explicit release operation, and writes two `Reminder` events for each confirmed occurrence: 15 and 5 minutes before its authoritative start instant. Each reminder carries the validated IANA building timezone and trusted organizer recipient UUID and is written to the Booking outbox in the same transaction as the meeting and occurrence rows.

Notification stores reminder effects as `SCHEDULED` rows. A scheduled worker makes due rows `PENDING` only after taking the same reservation-scoped PostgreSQL advisory lock used by reminder/terminal event processing and checking durable cancellation, completion, release, and automatic-release receipts. The reminder uniqueness index prevents equivalent schedule events from creating duplicate in-app reminders. A terminal event cancels scheduled reminders and unread reminders already made pending; late reminder events are ignored when a terminal receipt has committed. Current per-event channel preferences are applied at event creation and checked again before scheduled reminder delivery. Versioned notification templates and auditable administration are implemented for S7-05; asynchronous email is implemented by S7-06, with bounded retry/dead-letter/redrive added in S7-07. Room status/maintenance events use the user-approved `ROOM_EVENT_OUTBOX` and protected Booking recipient lookup scoped by Identity. Deployed RabbitMQ source credentials and per-routing-key ACLs remain to be configured and verified. The Notification consumer never treats missing events as evidence that no business action occurred.
