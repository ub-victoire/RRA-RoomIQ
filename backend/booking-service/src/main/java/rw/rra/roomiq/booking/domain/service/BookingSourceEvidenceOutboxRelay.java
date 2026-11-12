package rw.rra.roomiq.booking.domain.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import rw.rra.roomiq.booking.domain.repository.BookingSourceEvidenceOutboxEntry;
import rw.rra.roomiq.booking.domain.repository.BookingSourceEvidenceOutboxRepository;
import rw.rra.roomiq.booking.integration.BookingSourceEvidenceDeliveryClient;
import rw.rra.roomiq.booking.integration.BookingSourceEvidenceProperties;

import java.time.Clock;
import java.time.Instant;

@Component
@ConditionalOnProperty(name = "roomiq.booking.source-evidence.enabled", havingValue = "true")
public class BookingSourceEvidenceOutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(BookingSourceEvidenceOutboxRelay.class);
    private static final int MAX_AUTOMATIC_ATTEMPTS = 10;

    private final BookingSourceEvidenceOutboxRepository repository;
    private final BookingSourceEvidenceProperties properties;
    private final BookingSourceEvidenceDeliveryClient deliveryClient;
    private final BookingSourceEvidenceOutboxService outboxService;
    private final Clock clock;

    @Autowired
    public BookingSourceEvidenceOutboxRelay(BookingSourceEvidenceOutboxRepository repository,
                                            BookingSourceEvidenceProperties properties,
                                            BookingSourceEvidenceDeliveryClient deliveryClient,
                                            BookingSourceEvidenceOutboxService outboxService) {
        this(repository, properties, deliveryClient, outboxService, Clock.systemUTC());
    }

    BookingSourceEvidenceOutboxRelay(BookingSourceEvidenceOutboxRepository repository,
                                     BookingSourceEvidenceProperties properties,
                                     BookingSourceEvidenceDeliveryClient deliveryClient,
                                     BookingSourceEvidenceOutboxService outboxService,
                                     Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.deliveryClient = deliveryClient;
        this.outboxService = outboxService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${roomiq.booking.source-evidence.relay.fixed-delay-ms:1000}")
    @Transactional
    public void deliverPendingEvidence() {
        if (!properties.isEnabled()) {
            return;
        }
        reconcileConfigurationBlockedRows();
        for (BookingSourceEvidenceOutboxEntry entry : repository.claimDue(clock.instant(), 25)) {
            deliver(entry);
        }
    }

    private void reconcileConfigurationBlockedRows() {
        for (BookingSourceEvidenceOutboxEntry entry : repository.configurationBlocked(100)) {
            outboxService.applyCurrentMapping(entry);
        }
    }

    private void deliver(BookingSourceEvidenceOutboxEntry entry) {
        try {
            deliveryClient.deliver(entry);
            repository.markDelivered(entry.id(), clock.instant());
        } catch (RestClientResponseException exception) {
            String errorCode = "RLC_HTTP_" + exception.getStatusCode().value();
            if (exception.getStatusCode().is4xxClientError()) {
                repository.requireReconciliation(entry.id(), errorCode);
                log.error("Booking source evidence requires reconciliation eventId={} revision={} errorCode={}",
                        entry.eventId(), entry.sourceRevision(), errorCode);
                return;
            }
            defer(entry, errorCode);
        } catch (ResourceAccessException | ResponseStatusException exception) {
            defer(entry, exception.getClass().getSimpleName());
        } catch (IllegalStateException exception) {
            repository.requireReconciliation(entry.id(), "RLC_ACKNOWLEDGEMENT_INVALID");
            log.error("Booking source evidence requires reconciliation eventId={} revision={} errorCode={}",
                    entry.eventId(), entry.sourceRevision(), "RLC_ACKNOWLEDGEMENT_INVALID");
        } catch (RestClientException exception) {
            defer(entry, "RLC_CLIENT_ERROR");
        }
    }

    private void defer(BookingSourceEvidenceOutboxEntry entry, String errorCode) {
        boolean exhausted = entry.attemptCount() + 1 >= MAX_AUTOMATIC_ATTEMPTS;
        long backoffSeconds = Math.min(300, 1L << Math.min(entry.attemptCount(), 8));
        repository.defer(entry.id(), clock.instant().plusSeconds(backoffSeconds), errorCode, exhausted);
        if (exhausted) {
            log.error("Booking source evidence retries exhausted eventId={} revision={} errorCode={}",
                    entry.eventId(), entry.sourceRevision(), errorCode);
        } else {
            log.warn("Booking source evidence delivery deferred eventId={} revision={} attempt={} errorCode={}",
                    entry.eventId(), entry.sourceRevision(), entry.attemptCount() + 1, errorCode);
        }
    }
}
