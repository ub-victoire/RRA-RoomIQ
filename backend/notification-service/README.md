# Notification Service

Notification Service owns notification persistence and event consumption. It does not read or write another service's database and provides the authenticated in-app inbox workflow for a user’s own notifications.

## S7-03 inbox API

The service exposes a protected inbox API for the authenticated Identity account only. `GET /api/v1/notifications` (and `/api/v1/me/notifications`) returns a paginated, filterable list of the caller’s own notifications, and `PATCH /api/v1/notifications/{notificationId}/read` (and `/api/v1/me/notifications/{notificationId}/read`) marks only the current user’s record as read. The repository enforces ownership checks; cross-user access returns a not-found-style error and never exposes another user’s notification data.

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
# Same issuer/audience/key ID as Identity; mount the private key from a secret.
ROOMIQ_SERVICE_TOKEN_ISSUER=https://roomiq.local/identity
NOTIFICATION_IDENTITY_RECIPIENT_AUDIENCE=rra-roomiq-identity-recipient-resolution
NOTIFICATION_SERVICE_TOKEN_KEY_ID=notification-current
NOTIFICATION_SERVICE_TOKEN_PRIVATE_KEY_LOCATION=file:/run/secrets/notification-service-private.pem
# Optional service-specific broker account; shared RABBITMQ_USERNAME/PASSWORD
# are development defaults only.
NOTIFICATION_RABBITMQ_USERNAME=replace-with-notification-account
NOTIFICATION_RABBITMQ_PASSWORD=replace-with-local-secret
```

Provide RabbitMQ credentials with publish/consume access limited to the required exchanges and queues in deployed environments. The local `guest` default is for development only. Do not add credentials to tracked configuration files.

## Verification

From `backend/`:

```bash
mvn -pl notification-service -am test
mvn -pl identity-service -am test
mvn -pl common-web test
mvn test
```

The S7-01/S7-02 PostgreSQL and RabbitMQ integration tests use Testcontainers and require a running Docker daemon. S7-02 verification passed: focused Notification routing/transaction tests 20/20, complete Notification suite 39/39, Identity 62/62, common-web 6/6, Booking focused route-facts test 5/5, and the complete 10-module backend reactor 379/379. All had zero failures, errors, or skips. The first reactor run exposed an outdated Booking unit-test expectation for the v2 event overload; the assertion was updated and the full reactor rerun passed. See the central backend roadmap and `stage7Progress` contract for the verified policy and evidence.

## Event producer status

Booking and Room publish notification business events after source commits through their local transactional outboxes. Booking emits request/decision/cancellation/extension/completion events, emits `MeetingReleased` only from the protected explicit release operation, and writes two `Reminder` events for each confirmed occurrence: 15 and 5 minutes before its authoritative start instant. Each reminder carries the validated IANA building timezone and trusted organizer recipient UUID and is written to the Booking outbox in the same transaction as the meeting and occurrence rows.

Notification stores reminder effects as `SCHEDULED` rows. A scheduled worker makes due rows `PENDING` only after taking the same reservation-scoped PostgreSQL advisory lock used by reminder/terminal event processing and checking durable cancellation, completion, release, and automatic-release receipts. The reminder uniqueness index prevents equivalent schedule events from creating duplicate in-app reminders. A terminal event cancels scheduled reminders and unread reminders already made pending; late reminder events are ignored when a terminal receipt has committed. The schema change is limited to the user's approved Notification ERD addition. Reminder recipients beyond the organizer, channel preferences, email delivery, and templates remain later Stage 7 work. Room status/maintenance events use the user-approved `ROOM_EVENT_OUTBOX` and protected Booking recipient lookup scoped by Identity. Deployed RabbitMQ source credentials and per-routing-key ACLs remain to be configured and verified. No additional ERD change is authorized. The Notification consumer never treats missing events as evidence that no business action occurred.
