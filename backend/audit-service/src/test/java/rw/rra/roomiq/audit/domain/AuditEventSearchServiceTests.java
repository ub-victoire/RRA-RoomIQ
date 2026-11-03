package rw.rra.roomiq.audit.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import rw.rra.roomiq.audit.integration.AuditSearchAuthorizationDecision;
import rw.rra.roomiq.common.web.DomainException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AuditEventSearchServiceTests {
    private AuditEventRepository repository;
    private AuditEventSearchService service;

    @BeforeEach
    void setUp() {
        repository = mock(AuditEventRepository.class);
        service = new AuditEventSearchService(repository);
    }

    @Test
    void rejectsUnusableAuthorizationDecisionsBeforeRepositoryExecution() {
        UUID actorId = UUID.randomUUID();
        UUID buildingId = UUID.randomUUID();

        assertUnavailable(null);
        assertUnavailable(new AuditSearchAuthorizationDecision(null,
                AuditSearchAuthorizationDecision.AccessScope.GLOBAL, List.of()));
        assertUnavailable(new AuditSearchAuthorizationDecision(actorId, null, List.of()));
        assertUnavailable(new AuditSearchAuthorizationDecision(actorId,
                AuditSearchAuthorizationDecision.AccessScope.GLOBAL, List.of(buildingId)));
        assertUnavailable(new AuditSearchAuthorizationDecision(actorId,
                AuditSearchAuthorizationDecision.AccessScope.BUILDING_SCOPED, List.of()));
        assertUnavailable(new AuditSearchAuthorizationDecision(actorId,
                AuditSearchAuthorizationDecision.AccessScope.BUILDING_SCOPED, java.util.Arrays.asList(buildingId, null)));
        assertUnavailable(new AuditSearchAuthorizationDecision(actorId,
                AuditSearchAuthorizationDecision.AccessScope.BUILDING_SCOPED, List.of(buildingId, buildingId)));

        verifyNoSearch();
    }

    @Test
    void rejectsOutOfScopeBuildingFilterBeforeRepositoryExecution() {
        UUID trustedBuildingId = UUID.randomUUID();
        var decision = scopedDecision(UUID.randomUUID(), trustedBuildingId);
        var criteria = criteria(UUID.randomUUID(), null, null);

        assertThatThrownBy(() -> service.search(criteria, decision))
                .isInstanceOf(DomainException.class)
                .satisfies(exception -> {
                    DomainException domainException = (DomainException) exception;
                    assertThat(domainException.status()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(domainException.code()).isEqualTo("ACCESS_DENIED");
                });

        verifyNoSearch();
    }

    @Test
    void rejectsUnsupportedSortFieldBeforeRepositoryExecution() {
        var criteria = new AuditEventSearchCriteria(null, null, null, null, null, null, null,
                null, null, null, null, "metadata", null);

        assertThatThrownBy(() -> service.search(criteria, globalDecision(UUID.randomUUID())))
                .isInstanceOf(DomainException.class)
                .satisfies(exception -> {
                    DomainException domainException = (DomainException) exception;
                    assertThat(domainException.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(domainException.code()).isEqualTo("INVALID_SORT_FIELD");
                });

        verifyNoSearch();
    }

    @Test
    void rejectsInvalidCriteriaAndAppliesPaginationDefaults() {
        assertThat(new AuditEventSearchCriteria(null, null, null, null, null, null, null,
                null, null, null, null, null, null).page()).isZero();
        assertThat(new AuditEventSearchCriteria(null, null, null, null, null, null, null,
                null, null, null, null, null, null).size()).isEqualTo(20);

        assertThatThrownBy(() -> new AuditEventSearchCriteria(null, null, null, null, null, null, null,
                null, null, -1, 20, null, null))
                .isInstanceOf(DomainException.class)
                .satisfies(exception -> assertThat(((DomainException) exception).status())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> new AuditEventSearchCriteria(null, null, null, null, null, null, null,
                null, null, 0, 101, null, null))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new AuditEventSearchCriteria(null, null, null, null, null, null, null,
                null, null, 0, 20, null, "SIDEWAYS"))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new AuditEventSearchCriteria(null, null, null, null, null, null, null,
                java.time.Instant.parse("2026-01-02T00:00:00Z"),
                java.time.Instant.parse("2026-01-02T00:00:00Z"), null, null, null, null))
                .isInstanceOf(DomainException.class);

        verifyNoSearch();
    }

    private void assertUnavailable(AuditSearchAuthorizationDecision decision) {
        assertThatThrownBy(() -> service.search(criteria(null, null, null), decision))
                .isInstanceOf(DomainException.class)
                .satisfies(exception -> {
                    DomainException domainException = (DomainException) exception;
                    assertThat(domainException.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(domainException.code()).isEqualTo("IDENTITY_AUTHORIZATION_UNAVAILABLE");
                });
    }

    private void verifyNoSearch() {
        verify(repository, never()).search(anySpecification(), any(Pageable.class));
    }

    @SuppressWarnings("unchecked")
    private static Specification<AuditEvent> anySpecification() {
        return any(Specification.class);
    }

    private static AuditEventSearchCriteria criteria(UUID buildingId, String action, String sortBy) {
        return new AuditEventSearchCriteria(action, null, null, null, null, null, buildingId,
                null, null, null, null, sortBy, null);
    }

    private static AuditSearchAuthorizationDecision globalDecision(UUID actorId) {
        return new AuditSearchAuthorizationDecision(actorId,
                AuditSearchAuthorizationDecision.AccessScope.GLOBAL, List.of());
    }

    private static AuditSearchAuthorizationDecision scopedDecision(UUID actorId, UUID... buildingIds) {
        return new AuditSearchAuthorizationDecision(actorId,
                AuditSearchAuthorizationDecision.AccessScope.BUILDING_SCOPED, List.of(buildingIds));
    }
}
