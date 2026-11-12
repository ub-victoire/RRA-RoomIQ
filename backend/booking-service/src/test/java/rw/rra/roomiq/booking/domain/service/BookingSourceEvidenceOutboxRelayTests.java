package rw.rra.roomiq.booking.domain.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.mockito.InOrder;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import rw.rra.roomiq.booking.domain.repository.BookingSourceEvidenceOutboxEntry;
import rw.rra.roomiq.booking.domain.repository.BookingSourceEvidenceOutboxRepository;
import rw.rra.roomiq.booking.integration.BookingSourceEvidenceDeliveryClient;
import rw.rra.roomiq.booking.integration.BookingSourceEvidenceProperties;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BookingSourceEvidenceOutboxRelayTests {
    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
    private BookingSourceEvidenceOutboxRepository repository;
    private BookingSourceEvidenceDeliveryClient deliveryClient;
    private BookingSourceEvidenceOutboxService outboxService;
    private BookingSourceEvidenceProperties properties;
    private BookingSourceEvidenceOutboxRelay relay;

    @BeforeEach
    void setUp() {
        repository = mock(BookingSourceEvidenceOutboxRepository.class);
        deliveryClient = mock(BookingSourceEvidenceDeliveryClient.class);
        outboxService = mock(BookingSourceEvidenceOutboxService.class);
        properties = new BookingSourceEvidenceProperties();
        properties.setEnabled(true);
        relay = new BookingSourceEvidenceOutboxRelay(repository, properties, deliveryClient, outboxService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void onlyMarksEvidenceDeliveredAfterDeliveryReturnsDurableAcknowledgement() {
        BookingSourceEvidenceOutboxEntry entry = entry(0);
        when(repository.configurationBlocked(100)).thenReturn(List.of());
        when(repository.claimDue(NOW, 25)).thenReturn(List.of(entry));
        when(deliveryClient.deliver(entry)).thenReturn(
                new BookingSourceEvidenceDeliveryClient.DeliveryAcknowledgement(entry.eventId(), 1, "ACCEPTED"));

        relay.deliverPendingEvidence();

        InOrder order = inOrder(deliveryClient, repository);
        order.verify(deliveryClient).deliver(entry);
        order.verify(repository).markDelivered(entry.id(), NOW);
    }

    @Test
    void transientRlcOutageDefersRetryWithBoundedExponentialBackoff() {
        BookingSourceEvidenceOutboxEntry entry = entry(0);
        when(repository.configurationBlocked(100)).thenReturn(List.of());
        when(repository.claimDue(NOW, 25)).thenReturn(List.of(entry));
        when(deliveryClient.deliver(entry)).thenThrow(new ResourceAccessException("RLC unavailable"));

        relay.deliverPendingEvidence();

        verify(repository).defer(entry.id(), NOW.plusSeconds(1), "ResourceAccessException", false);
        verify(repository, never()).markDelivered(entry.id(), NOW);
    }

    @Test
    void permanentValidationFailureRequiresManualReconciliation() {
        BookingSourceEvidenceOutboxEntry entry = entry(0);
        when(repository.configurationBlocked(100)).thenReturn(List.of());
        when(repository.claimDue(NOW, 25)).thenReturn(List.of(entry));
        when(deliveryClient.deliver(entry)).thenThrow(HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST, "bad request", HttpHeaders.EMPTY, new byte[0],
                StandardCharsets.UTF_8));

        relay.deliverPendingEvidence();

        verify(repository).requireReconciliation(entry.id(), "RLC_HTTP_400");
        verify(repository, never()).markDelivered(entry.id(), NOW);
    }

    @Test
    void retryExhaustionStopsAutomaticDeliveryAndRequiresReconciliation() {
        BookingSourceEvidenceOutboxEntry entry = entry(9);
        when(repository.configurationBlocked(100)).thenReturn(List.of());
        when(repository.claimDue(NOW, 25)).thenReturn(List.of(entry));
        when(deliveryClient.deliver(entry)).thenThrow(new ResourceAccessException("RLC unavailable"));

        relay.deliverPendingEvidence();

        verify(repository).defer(entry.id(), NOW.plusSeconds(256), "ResourceAccessException", true);
    }

    @Test
    void disabledRelayDoesNotClaimOrSendEvidence() {
        properties.setEnabled(false);

        relay.deliverPendingEvidence();

        verify(repository, never()).claimDue(NOW, 25);
        verify(deliveryClient, never()).deliver(org.mockito.ArgumentMatchers.any());
    }

    private static BookingSourceEvidenceOutboxEntry entry(int attempts) {
        Instant now = NOW.minusSeconds(30);
        return new BookingSourceEvidenceOutboxEntry(UUID.randomUUID(), UUID.randomUUID(),
                "ADMIN_DIRECT_BOOKING", 1, UUID.randomUUID(), null, "TEST_CATEGORY", "RECORD_CREATED_AT",
                now, "DRAFT", now, now, now, null, null, null, attempts);
    }
}
