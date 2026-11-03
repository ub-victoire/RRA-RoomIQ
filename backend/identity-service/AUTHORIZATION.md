# Identity Authorization

## Enforcement model

Authorization is centralized in `IdentityAuthorizationService` and applied at the controller boundary with method security. Every request uses the validated JWT subject and reloads the current account/permission state from the identity database. A stale token therefore cannot preserve a permission after it is revoked or an account is disabled. Missing permission, inactive account, unknown scope, and cross-building access fail closed with HTTP 403 and the shared `ApiError` response.

The evaluator supports both database-backed role/permission assignments and trusted Spring authorities supplied by infrastructure. The service does not trust role, scope, status, or approval values supplied in request bodies.

## Organization Service delegation

`POST /api/v1/internal/authorization/organization` is authenticated by the same bearer-JWT/session filter as other protected identity APIs. It accepts only an action (`READ` or `MANAGE`) and evaluates the caller from the JWT subject and current database state. Active accounts may read; `MANAGE` requires the unscoped `IDENTITY_SYSTEM_ADMIN` permission. Organization Service calls this endpoint for each `/api/v1` request and treats Identity unavailability as denial, never as permission to proceed.

`POST /api/v1/internal/authorization/scheduling` accepts only `READ` or `MANAGE` and evaluates the authenticated account from current Identity state. Active accounts may read calendars, working windows, holidays, closure periods, recurrence rules, constraints, and availability; `MANAGE` requires `IDENTITY_SYSTEM_ADMIN`. The response includes the authenticated `actorUserId` for trusted creator attribution. Scheduling forwards the Identity-issued user bearer token on every `/api/v1` request, including `OPTIONS`, and fails closed on missing authentication, inactive accounts, denial, or Identity unavailability. The token supplies the user identity; this endpoint is not a separate machine-identity credential. No user, role, scope, or permission values are accepted from Scheduling request bodies. Scheduling has no API Gateway route in Stage 5; production deployment must separately restrict direct service and OpenAPI network access.

`POST /api/v1/internal/authorization/room-management` accepts an optional office-building ID. A non-null ID evaluates `ROOM_MANAGE` against the authenticated active user and that building's current scope; a null ID is reserved for global room/facility catalog mutations and requires `IDENTITY_SYSTEM_ADMIN`. Room Service forwards the original bearer token for every mutation. On success Identity returns `200` with the authenticated `actorUserId`; denial is `403`, missing/invalid authentication is `401`, and dependency failures are treated as denial by Room. Room never trusts client-supplied actor, role, or scope values.

`POST /api/v1/internal/authorization/booking` is bearer-protected and evaluates Booking permissions against the current Identity account, role assignments, building/department scope, and time-bounded `CG_BOOKING` privilege. It supports `AUTHENTICATE`, `REQUEST_CREATE`, `REQUEST_SUBMIT`, `REQUEST_LIST`, `REQUEST_READ`, `DIRECT_CREATE`, `APPROVE`, `RESERVATION_LIFECYCLE`, `CANCEL`, `EXTENSION_REQUEST`, and `EXTENSION_DECIDE`. Identity derives `actorUserId` from the authenticated principal; the request schema deliberately has no actor, role, status, permission, or scope fields. Request creation is restricted to the active caller's own current department/building and requires `BOOKING_REQUEST_CREATE`; direct-booking permission does not grant request creation. Draft submission additionally requires the authenticated caller to equal the persisted requester. Own-list/read decisions are limited to the caller's current building and request capability; building-wide list/read decisions require `BOOKING_APPROVE` in that building. Reviewer reads do not authorize draft submission. `DIRECT_CREATE` returns Admin/Super Admin authority from current direct-booking permission/scope or Secretary authority from current request permission/scope; VIP eligibility is evaluated against the active `CG_BOOKING` grant and server time. `RESERVATION_LIFECYCLE` permits the organizer only with `BOOKING_LIFECYCLE_OWN` and an exact match to their current department and building; scoped administrators require `BOOKING_LIFECYCLE_MANAGE` in the reservation building, while `IDENTITY_SYSTEM_ADMIN` is global. For an authorized `APPROVE` decision, the response also includes the persisted requester's current active `resourceOwnerUserId` and trusted display name (`displayName`, falling back to `fullName`); lifecycle authorization echoes the persisted organizer ID so Booking can verify scope facts. Booking first checks identity/scope, then validates Room-owned room class and rechecks VIP authority when the room is VIP; caller-provided privilege or room classification is never trusted. The active Room rule's `approvalRequired` flag applies to all direct-booking authorities: when true Booking creates a pending request and does not create a reservation or meeting until a different authorized approver completes the decision. Booking forwards the caller token and fails closed when Identity is unavailable, returns an incomplete or inconsistent response, or denies the current policy. The endpoint is internal integration, not a public Booking API.

`POST /api/v1/internal/authorization/notification-delivery` authorizes Notification Service operational health, dead-letter inspection, and redrive only for an active, unscoped `SUPER_ADMIN` holding the dedicated `NOTIFICATION_DELIVERY_MANAGE` permission. Template-management permission does not imply delivery-management permission. Identity derives the actor from the current bearer principal; Notification records that actor with each redrive and does not read Identity's database directly. The permission is cataloged by Flyway V4 and must be explicitly assigned to an approved Super Admin role by an authorized operator; the migration does not grant it automatically.

`POST /api/v1/internal/authorization/audit-search` accepts no request body and requires a validated bearer token. Identity derives the user ID from the JWT subject and checks current database state: an active, global `SUPER_ADMIN` assignment with `AUDIT_READ` receives `accessScope: GLOBAL`; an active `ADMIN` assignment with `AUDIT_READ` and one or more non-null building scopes receives `accessScope: BUILDING_SCOPED` and only those assigned `buildingIds`. An empty scope set, an unscoped `ADMIN`, an inactive user, a missing permission, or a revoked assignment is denied. Caller-supplied identity, role, permission, global-access, and building-scope values do not affect the decision. Flyway V5 adds `AUDIT_READ` to the permission catalog only; it does not grant the permission to any role. Identity stores building references as UUIDs and has no building-lifecycle state or Organization lookup, so this decision verifies current persisted assignments, not whether a referenced building remains active in Organization.

`POST /api/v1/internal/authorization/meeting` is bearer-protected and evaluates `READ` or `MANAGE` using the caller derived from the Identity token plus persisted Booking organizer, department, building, and visibility facts. PRIVATE reads allow the active in-scope organizer or a current reviewer with `BOOKING_APPROVE` in the building (or global `IDENTITY_SYSTEM_ADMIN`). INTERNAL reads additionally allow any active account currently assigned to the same office building; PUBLIC visibility does not broaden access through this API. S6-11 public-card access is a separate token-scoped Booking route and does not alter this authorization policy. Management is limited to the active in-scope organizer or an authorized reviewer. An optional participant user ID is accepted only for `MANAGE`; Identity returns its trusted display name only if the target account is active and currently assigned to the same building. Responses echo the owner/scope/visibility/target facts for Booking's consistency check and derive the actor from the validated caller; no actor, role, permission, or scope may be caller-selected. Booking propagates the caller token and fails closed on denial, unavailable Identity, or inconsistent response facts.

## Permission code catalog

Create permission rows through the Super Admin API after bootstrap. These stable codes are consumed by centralized policy checks:

- `IDENTITY_SYSTEM_ADMIN`: global identity administration and all building scopes.
- `IDENTITY_USER_READ`: read users in an assigned office-building scope.
- `IDENTITY_USER_MANAGE`: create/update/status/delete users in an assigned office-building scope.
- `IDENTITY_USER_ROLE_READ`: read role assignments in an assigned scope.
- `IDENTITY_USER_ROLE_ASSIGN`: assign/revoke Secretary roles in an assigned scope.
- `IDENTITY_PRIVILEGE_READ`: read privileges in an assigned scope.
- `IDENTITY_PRIVILEGE_GRANT`: grant/revoke privileges in an assigned scope.
- `IDENTITY_ROLE_READ`, `IDENTITY_ROLE_MANAGE`, `IDENTITY_PERMISSION_READ`, `IDENTITY_PERMISSION_MANAGE`, `IDENTITY_ROLE_PERMISSION_MANAGE`: global role and permission catalog operations; catalog mutations require `IDENTITY_SYSTEM_ADMIN`.
- `BOOKING_REQUEST_CREATE`: create booking requests for the authenticated user's own department and office.
- `BOOKING_DIRECT_CREATE`: create direct bookings in assigned scope.
- `BOOKING_APPROVE`: approve/reject requests in assigned scope, never the actor's own request.
- `BOOKING_CANCEL`: cancel bookings in assigned scope; may override an elapsed Room cancellation deadline only with an explanation.
- `BOOKING_CANCEL_OWN`: cancel the authenticated user's own eligible booking before the configured deadline.
- `BOOKING_LIFECYCLE_MANAGE`: check in/complete reservations within an assigned office-building scope.
- `BOOKING_LIFECYCLE_OWN`: check in/complete the authenticated user's own reservation within current department/building scope.
- `BOOKING_EXTENSION_REQUEST`: request an extension for the authenticated user's own booking.
- `BOOKING_EXTENSION_DECIDE`: decide extension requests in assigned scope, never the actor's own request.
- `ROOM_MANAGE`: manage rooms and room configuration in assigned scope.
- `NOTIFICATION_TEMPLATE_MANAGE`: manage Notification templates as an active global Super Admin.
- `NOTIFICATION_DELIVERY_MANAGE`: inspect aggregate email delivery operations and idempotently redrive dead letters as an active global Super Admin.
- `AUDIT_READ`: authorize Audit Search for an active global Super Admin or an active building-scoped Admin with trusted assigned building scopes.

`CG_BOOKING` remains a separate time-bounded privilege, not a role or permission. It permits eligible booking workflows for VIP rooms only when the user's account, scope, room rules, and all scheduling constraints also allow it.

## Role policy

- **Super Admin:** assign the unscoped `IDENTITY_SYSTEM_ADMIN` permission to the controlled `SUPER_ADMIN` role. This grants system-wide administration.
- **Admin:** assign identity-management and operational permissions to `ADMIN`; each `USER_ROLE` grant must carry the authorized `scope_office_building_id`. Scoped Admins can manage only Secretaries or unassigned `PENDING` users in that building. They cannot create/modify role or permission definitions, assign `ADMIN`/`SUPER_ADMIN`, manage privileged accounts, or move users outside scopes they manage.
- **Secretary:** assign booking request, own-cancellation, and extension-request permissions to `SECRETARY`, scoped to the user's office. A Secretary may read their own identity and may not administer users, roles, permissions, or privileges.
- **CG:** grant `CG_BOOKING` as a separate privilege. Scoped Admins can grant/revoke it only for eligible users in their building scope; Super Admins can do so globally. Privilege grant attribution always comes from the authenticated actor, not the request body.

## First Super Admin bootstrap

There is deliberately no unauthenticated HTTP bootstrap endpoint. An authorized database operator performs this one-time setup before exposing the service:

1. Generate the initial password hash using the application's BCrypt encoder at work factor 12. Never place a plaintext password in SQL, shell history, source control, or logs.
2. Insert the first `APP_USER` with the approved Super Admin email, the generated hash, status `ACTIVE`, and an application-generated UUID.
3. Insert the `SUPER_ADMIN` role (`is_system=true`) and the `IDENTITY_SYSTEM_ADMIN` permission.
4. Insert the `ROLE_PERMISSION` assignment connecting that role and permission.
5. Insert the `USER_ROLE` assignment for the new user with `scope_office_building_id=NULL` and `granted_by_user_id=NULL`.
6. Verify login, a protected identity operation, and audit/security monitoring before removing operator access.

All later role, permission, and user-role changes use authenticated APIs and the centralized policy evaluator. The initial administrator identity and password hash must be provisioned through the organization's approved secret-handling process.

## Policy test coverage

`IdentityAuthorizationTests` exercises scoped Admin permissions, cross-building denial, Secretary self-read, role escalation denial, scoped `CG_BOOKING`, booking approval/direct-booking decisions, permission revocation, and Super Admin global access against H2.

## Error and audit contract

Identity domain failures use stable machine-readable `ApiError.code` values rather than status-derived codes. Current codes include `AUTHENTICATION_FAILED`, `INVALID_REFRESH_TOKEN`, `USER_NOT_FOUND`, `DUPLICATE_USER_IDENTIFIER`, `ROLE_CODE_CONFLICT`, `PERMISSION_CODE_CONFLICT`, `ROLE_PERMISSION_CONFLICT`, `USER_ROLE_CONFLICT`, `USER_PRIVILEGE_NOT_FOUND`, `PRIVILEGE_GRANT_CONFLICT`, `PRIVILEGE_ALREADY_INACTIVE`, and `INVALID_PRIVILEGE_VALIDITY_WINDOW`. Security filters use `AUTHENTICATION_REQUIRED` and `ACCESS_DENIED`; validation failures use `VALIDATION_ERROR` or `CONSTRAINT_VIOLATION`.

Every response error includes a correlation ID. A valid caller ID is restricted to 1-128 ASCII letters, digits, `.`, `_`, and `-`; invalid/missing IDs are replaced consistently across the response header, error envelope, and MDC.

Identity writes audit envelopes to its transactional outbox and publishes them to the durable `roomiq.audit.identity.v1` queue using the `audit.identity` routing key on `roomiq.events.v1`. The relay requires a positive correlated publisher confirm and no mandatory return before marking a row published. Events carry a stable event ID, which consumers must use for deduplication. The outbox relay retries transient, NACKed, timed-out, or unroutable publishes with capped exponential backoff. Event metadata contains identifiers and workflow-safe attributes only; passwords, hashes, tokens, email addresses, and profile values are excluded. Audit persistence/search remains owned by the Audit Service.
