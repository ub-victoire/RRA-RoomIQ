package rw.rra.roomiq.lifecycle.retention;

public enum HoldStatus {
    PENDING_BLOCK,
    ACTIVE,
    RELEASE_PENDING,
    RELEASE_ENFORCEMENT_PENDING,
    RELEASED,
    REJECTED,
    CONFLICTED,
    UNACKNOWLEDGED,
    UNKNOWN
}
