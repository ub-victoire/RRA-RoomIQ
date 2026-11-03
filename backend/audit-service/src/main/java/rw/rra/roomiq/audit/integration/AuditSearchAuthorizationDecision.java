package rw.rra.roomiq.audit.integration;

import java.util.List;
import java.util.UUID;

public record AuditSearchAuthorizationDecision(
        UUID actorUserId,
        AccessScope accessScope,
        List<UUID> buildingIds) {

    public enum AccessScope {
        GLOBAL,
        BUILDING_SCOPED
    }
}
