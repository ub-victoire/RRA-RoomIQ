package rw.rra.roomiq.audit.domain;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import rw.rra.roomiq.audit.integration.AuditSourceEvidenceDeliveryClient;
import rw.rra.roomiq.audit.integration.AuditSourceEvidenceProperties;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditSourceEvidenceOutboxRelayTests {
    private static final Instant NOW = Instant.parse("2026-02-01T10:00:00Z");

    @Test
    void marksEvidenceDeliveredOnlyAfterTheDeliveryClientReturnsDurableAcknowledgement() {
        var fixture = fixture(entry(0));
        when(fixture.client.deliver(fixture.entry))
                .thenReturn(new AuditSourceEvidenceDeliveryClient.DeliveryAcknowledgement(
                        fixture.entry.auditEventId(), 1, "ACCEPTED"));

        fixture.relay.deliverPendingEvidence();

        verify(fixture.repository).markDelivered(fixture.entry.auditEventId(), NOW);
    }

    @Test
    void transientUnavailableRlcIsDeferredWithBoundedBackoff() {
        var fixture = fixture(entry(0));
        when(fixture.client.deliver(fixture.entry)).thenThrow(new ResourceAccessException("unavailable"));

        fixture.relay.deliverPendingEvidence();

        verify(fixture.repository).defer(
                fixture.entry.auditEventId(), NOW.plusSeconds(1), "ResourceAccessException", false);
    }

    @Test
    void permanentRlcRejectionRequiresOperationalReconciliation() {
        var fixture = fixture(entry(0));
        when(fixture.client.deliver(fixture.entry)).thenThrow(
                HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "invalid", null, null, null));

        fixture.relay.deliverPendingEvidence();

        verify(fixture.repository).requireReconciliation(fixture.entry.auditEventId(), "RLC_HTTP_400");
    }

    private static Fixture fixture(AuditSourceEvidenceOutboxEntry entry) {
        var repository = mock(AuditSourceEvidenceOutboxRepository.class);
        var properties = mock(AuditSourceEvidenceProperties.class);
        var client = mock(AuditSourceEvidenceDeliveryClient.class);
        var outboxService = mock(AuditSourceEvidenceOutboxService.class);
        when(properties.isEnabled()).thenReturn(true);
        when(repository.configurationBlocked(100)).thenReturn(List.of());
        when(repository.claimDue(NOW, 25)).thenReturn(List.of(entry));
        return new Fixture(entry, repository, client,
                new AuditSourceEvidenceOutboxRelay(repository, properties, client, outboxService,
                        Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    private static AuditSourceEvidenceOutboxEntry entry(int attempts) {
        return new AuditSourceEvidenceOutboxEntry(UUID.randomUUID(), "AUTHENTICATION_FAILED",
                "TEST_AUDIT_CATEGORY", "OCCURRED_AT", NOW.minusSeconds(1),
                NOW.minusSeconds(2), NOW, "correlation-1", attempts);
    }

    private record Fixture(AuditSourceEvidenceOutboxEntry entry,
                           AuditSourceEvidenceOutboxRepository repository,
                           AuditSourceEvidenceDeliveryClient client,
                           AuditSourceEvidenceOutboxRelay relay) {
    }
}
