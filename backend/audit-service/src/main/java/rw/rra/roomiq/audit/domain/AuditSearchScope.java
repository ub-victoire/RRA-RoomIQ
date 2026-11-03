package rw.rra.roomiq.audit.domain;

import org.springframework.http.HttpStatus;
import rw.rra.roomiq.audit.integration.AuditSearchAuthorizationDecision;
import rw.rra.roomiq.common.web.DomainException;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

record AuditSearchScope(
        UUID actorUserId,
        AuditSearchAuthorizationDecision.AccessScope accessScope,
        List<UUID> buildingIds) {

    AuditSearchScope {
        buildingIds = List.copyOf(buildingIds);
    }

    static AuditSearchScope from(AuditSearchAuthorizationDecision decision) {
        if (decision == null || decision.actorUserId() == null || decision.accessScope() == null
                || decision.buildingIds() == null
                || decision.buildingIds().stream().anyMatch(buildingId -> buildingId == null)
                || new HashSet<>(decision.buildingIds()).size() != decision.buildingIds().size()) {
            throw unusableDecision();
        }

        if (decision.accessScope() == AuditSearchAuthorizationDecision.AccessScope.GLOBAL) {
            if (!decision.buildingIds().isEmpty()) {
                throw unusableDecision();
            }
        } else if (decision.accessScope() == AuditSearchAuthorizationDecision.AccessScope.BUILDING_SCOPED) {
            if (decision.buildingIds().isEmpty()) {
                throw unusableDecision();
            }
        } else {
            throw unusableDecision();
        }

        return new AuditSearchScope(decision.actorUserId(), decision.accessScope(), decision.buildingIds());
    }

    boolean isBuildingScoped() {
        return accessScope == AuditSearchAuthorizationDecision.AccessScope.BUILDING_SCOPED;
    }

    private static DomainException unusableDecision() {
        return new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_AUTHORIZATION_UNAVAILABLE",
                "Identity authorization is unavailable; the request cannot proceed");
    }
}
