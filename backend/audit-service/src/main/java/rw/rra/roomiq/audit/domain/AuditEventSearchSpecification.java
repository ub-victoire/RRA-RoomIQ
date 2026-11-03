package rw.rra.roomiq.audit.domain;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

final class AuditEventSearchSpecification {
    private AuditEventSearchSpecification() {
    }

    static Specification<AuditEvent> from(AuditEventSearchCriteria criteria, AuditSearchScope scope) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (scope.isBuildingScoped()) {
                predicates.add(root.get("scopeOfficeBuildingId").in(scope.buildingIds()));
            }
            if (criteria.action() != null) {
                predicates.add(builder.equal(root.get("action"), criteria.action()));
            }
            if (criteria.resourceType() != null) {
                predicates.add(builder.equal(root.get("resourceType"), criteria.resourceType()));
            }
            if (criteria.resourceId() != null) {
                predicates.add(builder.equal(root.get("resourceId"), criteria.resourceId()));
            }
            if (criteria.actorUserId() != null) {
                predicates.add(builder.equal(root.get("actorUserId"), criteria.actorUserId()));
            }
            if (criteria.outcome() != null) {
                predicates.add(builder.equal(root.get("outcome"), criteria.outcome()));
            }
            if (criteria.correlationId() != null) {
                predicates.add(builder.equal(root.get("correlationId"), criteria.correlationId()));
            }
            if (criteria.buildingId() != null) {
                predicates.add(builder.equal(root.get("scopeOfficeBuildingId"), criteria.buildingId()));
            }
            if (criteria.occurredFrom() != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("occurredAt"), criteria.occurredFrom()));
            }
            if (criteria.occurredTo() != null) {
                predicates.add(builder.lessThan(root.get("occurredAt"), criteria.occurredTo()));
            }

            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }
}
