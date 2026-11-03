package rw.rra.roomiq.identity.domain.dto;

import java.util.List;
import java.util.UUID;

public record AuditSearchAuthorizationResponse(
        UUID actorUserId,
        AccessScope accessScope,
        List<UUID> buildingIds) {

    public AuditSearchAuthorizationResponse {
        buildingIds = List.copyOf(buildingIds);
    }

    public enum AccessScope {
        GLOBAL,
        BUILDING_SCOPED
    }
}
