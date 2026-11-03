package rw.rra.roomiq.audit.domain;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import rw.rra.roomiq.audit.integration.AuditSearchAuthorizationDecision;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "roomiq.auth.jwt-secret=test-only-signing-key-at-least-32-bytes-long",
        "roomiq.audit.notification.events.enabled=false",
        "roomiq.audit.identity.events.enabled=false"
})
@Testcontainers
class AuditEventSearchPostgresTests {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")
            .withInitScript("audit-test-roles.sql");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "roomiq_audit_app");
        registry.add("spring.datasource.password", () -> "audit-app-test-password");
        registry.add("spring.flyway.user", () -> "roomiq_audit_migrator");
        registry.add("spring.flyway.password", () -> "audit-migrator-test-password");
    }

    @Autowired
    private IdentityAuditEventProcessor processor;

    @Autowired
    private AuditEventSearchService service;

    @Test
    void globalSearchSupportsExactFiltersAndOptionalBuildingNarrowing() {
        UUID actorId = UUID.randomUUID();
        UUID buildingA = UUID.randomUUID();
        UUID buildingB = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-02-01T10:00:00Z");
        save("SEARCH_TEST_A", "ROOM", resourceId, actorId, buildingA, "SUCCESS", "search-corr-a", occurredAt);
        save("SEARCH_TEST_A", "ROOM", resourceId, actorId, buildingB, "SUCCESS", "search-corr-a", occurredAt);
        save("SEARCH_TEST_B", "ROOM", resourceId, actorId, buildingA, "SUCCESS", "search-corr-a", occurredAt);

        var global = search(criteria("SEARCH_TEST_A", "ROOM", resourceId, actorId,
                "SUCCESS", "search-corr-a", null, null, null, 0, 20, null, null),
                globalDecision(UUID.randomUUID()));
        assertThat(global.getTotalElements()).isEqualTo(2);

        var narrowed = search(criteria("SEARCH_TEST_A", "ROOM", resourceId, actorId,
                "SUCCESS", "search-corr-a", buildingA, null, null, 0, 20, null, null),
                globalDecision(UUID.randomUUID()));
        assertThat(narrowed.getTotalElements()).isEqualTo(1);
        assertThat(narrowed.getContent()).allMatch(event -> event.getScopeOfficeBuildingId().equals(buildingA));
    }

    @Test
    void scopedSearchRestrictsBothContentAndCountAndExcludesNullBuildingRecords() {
        UUID trustedBuilding = UUID.randomUUID();
        UUID otherBuilding = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        save("SCOPED_SEARCH", "ROOM", UUID.randomUUID(), actorId, trustedBuilding,
                "SUCCESS", null, Instant.parse("2026-02-02T10:00:00Z"));
        save("SCOPED_SEARCH", "ROOM", UUID.randomUUID(), actorId, otherBuilding,
                "SUCCESS", null, Instant.parse("2026-02-02T10:01:00Z"));
        save("SCOPED_SEARCH", "ROOM", UUID.randomUUID(), null, null,
                "DENIED", null, Instant.parse("2026-02-02T10:02:00Z"));

        var results = search(criteria("SCOPED_SEARCH", null, null, null, null, null,
                        null, null, null, 0, 1, null, null),
                scopedDecision(actorId, trustedBuilding));

        assertThat(results.getContent()).hasSize(1);
        assertThat(results.getContent().getFirst().getScopeOfficeBuildingId()).isEqualTo(trustedBuilding);
        assertThat(results.getTotalElements()).isEqualTo(1);
        assertThat(results.getTotalPages()).isEqualTo(1);
    }

    @Test
    void nullableFieldsAndExplicitOffsetDateBoundsBehaveAsSpecified() {
        UUID actorId = UUID.randomUUID();
        Instant lower = Instant.parse("2026-02-03T10:00:00Z");
        Instant upper = Instant.parse("2026-02-03T11:00:00Z");
        save("DATE_BOUNDARY", "ROOM", null, null, null, "DENIED", null, lower);
        save("DATE_BOUNDARY", "ROOM", null, actorId, null, "DENIED", null, upper.minusNanos(1_000));
        save("DATE_BOUNDARY", "ROOM", null, actorId, null, "DENIED", null, upper);

        var inRange = search(criteria("DATE_BOUNDARY", "ROOM", null, null, "DENIED",
                        null, null, lower, upper, 0, 20, null, null),
                globalDecision(UUID.randomUUID()));

        assertThat(inRange.getTotalElements()).isEqualTo(2);
        assertThat(inRange.getContent()).anyMatch(event -> event.getActorUserId() == null
                && event.getResourceId() == null
                && event.getScopeOfficeBuildingId() == null
                && event.getCorrelationId() == null);
    }

    @Test
    void defaultAndExplicitSortingUseStableIdTieBreakerAndDatabasePagination() {
        Instant time = Instant.parse("2026-02-04T10:00:00Z");
        UUID firstId = save("SORT_TEST", "ROOM", null, null, null, "SUCCESS", null, time);
        UUID secondId = save("SORT_TEST", "ROOM", null, null, null, "SUCCESS", null, time);
        UUID thirdId = save("SORT_TEST", "ROOM", null, null, null, "SUCCESS", null, time);

        var defaultPage = search(criteria("SORT_TEST", null, null, null, null, null,
                        null, null, null, null, null, null, null),
                globalDecision(UUID.randomUUID()));
        assertThat(defaultPage.getNumber()).isZero();
        assertThat(defaultPage.getSize()).isEqualTo(20);
        List<UUID> descendingIds = List.of(firstId, secondId, thirdId).stream()
                .sorted(Comparator.comparing(UUID::toString).reversed())
                .toList();
        assertThat(defaultPage.getContent()).extracting(AuditEvent::getId)
                .containsExactlyElementsOf(descendingIds);

        var actionPage = search(criteria("SORT_TEST", null, null, null, null, null,
                        null, null, null, 0, 2, "action", "ASC"),
                globalDecision(UUID.randomUUID()));
        assertThat(actionPage.getContent()).hasSize(2);
        assertThat(actionPage.getContent()).extracting(AuditEvent::getId)
                .containsExactlyElementsOf(descendingIds.subList(0, 2));
        assertThat(actionPage.getTotalElements()).isEqualTo(3);

        var beyondLast = search(criteria("SORT_TEST", null, null, null, null, null,
                        null, null, null, 2, 2, "action", "ASC"),
                globalDecision(UUID.randomUUID()));
        assertThat(beyondLast.getContent()).isEmpty();
        assertThat(beyondLast.getTotalElements()).isEqualTo(3);

        var noMatches = search(criteria("NO_SUCH_SEARCH_ACTION", null, null, null, null, null,
                        null, null, null, 0, 20, null, null),
                globalDecision(UUID.randomUUID()));
        assertThat(noMatches.getContent()).isEmpty();
        assertThat(noMatches.getTotalElements()).isZero();
        assertThat(noMatches.getTotalPages()).isZero();
    }

    @Test
    void parameterizedExactFilterDoesNotInterpretFilterTextAsSql() {
        String literalAction = "SEARCH' OR '1'='1";
        save(literalAction, "ROOM", null, null, null, "SUCCESS", null,
                Instant.parse("2026-02-05T10:00:00Z"));
        save("OTHER_ACTION", "ROOM", null, null, null, "SUCCESS", null,
                Instant.parse("2026-02-05T10:01:00Z"));

        var results = search(criteria(literalAction, null, null, null, null, null,
                        null, null, null, 0, 20, null, null),
                globalDecision(UUID.randomUUID()));

        assertThat(results.getTotalElements()).isEqualTo(1);
        assertThat(results.getContent()).allMatch(event -> event.getAction().equals(literalAction));
    }

    private UUID save(String action, String resourceType, UUID resourceId, UUID actorId, UUID buildingId,
                      String outcome, String correlationId, Instant occurredAt) {
        UUID eventId = UUID.randomUUID();
        processor.process(new IdentityAuditEvent(eventId, 1, action, occurredAt, actorId,
                resourceType, resourceId, buildingId, outcome, correlationId, "{}"));
        return eventId;
    }

    private Page<AuditEvent> search(AuditEventSearchCriteria criteria,
                                    AuditSearchAuthorizationDecision decision) {
        return service.search(criteria, decision);
    }

    private static AuditEventSearchCriteria criteria(String action, String resourceType, UUID resourceId,
                                                      UUID actorId, String outcome, String correlationId,
                                                      UUID buildingId, Instant occurredFrom, Instant occurredTo,
                                                      Integer page, Integer size, String sortBy,
                                                      String sortDirection) {
        return new AuditEventSearchCriteria(action, resourceType, resourceId, actorId, outcome, correlationId,
                buildingId, occurredFrom, occurredTo, page, size, sortBy, sortDirection);
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
