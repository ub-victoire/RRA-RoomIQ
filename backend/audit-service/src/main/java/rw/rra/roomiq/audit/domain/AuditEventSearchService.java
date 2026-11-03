package rw.rra.roomiq.audit.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.rra.roomiq.audit.integration.AuditSearchAuthorizationDecision;
import rw.rra.roomiq.common.web.DomainException;

import java.util.Map;

@Service
public class AuditEventSearchService {
    private static final Map<String, String> SORT_PROPERTIES = Map.of(
            "id", "id",
            "actorUserId", "actorUserId",
            "action", "action",
            "resourceType", "resourceType",
            "resourceId", "resourceId",
            "buildingId", "scopeOfficeBuildingId",
            "outcome", "outcome",
            "correlationId", "correlationId",
            "occurredAt", "occurredAt");

    private final AuditEventRepository repository;

    public AuditEventSearchService(AuditEventRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Page<AuditEvent> search(AuditEventSearchCriteria criteria,
                                   AuditSearchAuthorizationDecision authorizationDecision) {
        AuditSearchScope scope = AuditSearchScope.from(authorizationDecision);
        if (criteria.buildingId() != null && scope.isBuildingScoped()
                && !scope.buildingIds().contains(criteria.buildingId())) {
            throw new DomainException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access is denied");
        }

        String sortProperty = SORT_PROPERTIES.get(criteria.sortBy());
        if (sortProperty == null) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_SORT_FIELD",
                    "Requested sort field is not supported");
        }

        Sort sort = Sort.by(Sort.Direction.valueOf(criteria.sortDirection()), sortProperty)
                .and(Sort.by(Sort.Direction.DESC, "id"));
        PageRequest pageRequest = PageRequest.of(criteria.page(), criteria.size(), sort);
        return repository.search(AuditEventSearchSpecification.from(criteria, scope), pageRequest);
    }
}
