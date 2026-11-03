package rw.rra.roomiq.identity.domain.security;

public final class IdentityPermissionCodes {
    public static final String SYSTEM_ADMIN = "IDENTITY_SYSTEM_ADMIN";
    public static final String USER_READ = "IDENTITY_USER_READ";
    public static final String USER_MANAGE = "IDENTITY_USER_MANAGE";
    public static final String USER_ROLE_READ = "IDENTITY_USER_ROLE_READ";
    public static final String USER_ROLE_ASSIGN = "IDENTITY_USER_ROLE_ASSIGN";
    public static final String PRIVILEGE_READ = "IDENTITY_PRIVILEGE_READ";
    public static final String PRIVILEGE_GRANT = "IDENTITY_PRIVILEGE_GRANT";
    public static final String ROLE_READ = "IDENTITY_ROLE_READ";
    public static final String ROLE_MANAGE = "IDENTITY_ROLE_MANAGE";
    public static final String PERMISSION_READ = "IDENTITY_PERMISSION_READ";
    public static final String PERMISSION_MANAGE = "IDENTITY_PERMISSION_MANAGE";
    public static final String ROLE_PERMISSION_MANAGE = "IDENTITY_ROLE_PERMISSION_MANAGE";
    public static final String BOOKING_REQUEST_CREATE = "BOOKING_REQUEST_CREATE";
    public static final String BOOKING_DIRECT_CREATE = "BOOKING_DIRECT_CREATE";
    public static final String BOOKING_APPROVE = "BOOKING_APPROVE";
    public static final String BOOKING_CANCEL = "BOOKING_CANCEL";
    public static final String BOOKING_CANCEL_OWN = "BOOKING_CANCEL_OWN";
    public static final String BOOKING_LIFECYCLE_MANAGE = "BOOKING_LIFECYCLE_MANAGE";
    public static final String BOOKING_LIFECYCLE_OWN = "BOOKING_LIFECYCLE_OWN";
    public static final String BOOKING_EXTENSION_REQUEST = "BOOKING_EXTENSION_REQUEST";
    public static final String BOOKING_EXTENSION_DECIDE = "BOOKING_EXTENSION_DECIDE";
    public static final String ROOM_MANAGE = "ROOM_MANAGE";
    public static final String NOTIFICATION_TEMPLATE_MANAGE = "NOTIFICATION_TEMPLATE_MANAGE";
    public static final String NOTIFICATION_DELIVERY_MANAGE = "NOTIFICATION_DELIVERY_MANAGE";

    private IdentityPermissionCodes() {
    }
}
