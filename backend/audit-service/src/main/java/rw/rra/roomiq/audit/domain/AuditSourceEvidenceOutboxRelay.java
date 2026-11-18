package rw.rra.roomiq.audit.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import rw.rra.roomiq.audit.integration.AuditSourceEvidenceDeliveryClient;
import rw.rra.roomiq.audit.integration.AuditSourceEvidenceProperties;

import java.time.Clock;

@Component
@ConditionalOnProperty(name = "roomiq.audit.source-evidence.enabled", havingValue = "true")
public class AuditSourceEvidenceOutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(AuditSourceEvidenceOutboxRelay.class);
    private static final int MAX_AUTOMATIC_ATTEMPTS = 10;

    private final AuditSourceEvidenceOutboxRepository repository;
    private final AuditSourceEvidenceProperties properties;
    private final AuditSourceEvidenceDeliveryClient deliveryClient;
    private final AuditSourceEvidenceOutboxService outboxService;
    private final Clock clock;

    public AuditSourceEvidenceOutboxRelay(AuditSourceEvidenceOutboxRepository repository,
                                          AuditSourceEvidenceProperties properties,
                                          AuditSourceEvidenceDeliveryClient deliveryClient,
                                          AuditSourceEvidenceOutboxService outboxService) {
        this(repository, properties, deliveryClient, outboxService, Clock.systemUTC());
    }

    AuditSourceEvidenceOutboxRelay(AuditSourceEvidenceOutboxRepository repository,
                                   AuditSourceEvidenceProperties properties,
                                   AuditSourceEvidenceDeliveryClient deliveryClient,
                                   AuditSourceEvidenceOutboxService outboxService, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.deliveryClient = deliveryClient;
        this.outboxService = outboxService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${roomiq.audit.source-evidence.relay-fixed-delay-ms:1000}")
    @Transactional
    public void deliverPendingEvidence() {
        if (!properties.isEnabled()) {
            return;
        }
        reconcileConfigurationBlockedRows();
        for (AuditSourceEvidenceOutboxEntry entry : repository.claimDue(clock.instant(), 25)) {
            deliver(entry);
        }
    }

    private void reconcileConfigurationBlockedRows() {
        for (AuditSourceEvidenceOutboxEntry entry : repository.configurationBlocked(100)) {
            outboxService.applyCurrentMapping(entry);
        }
    }

    private void deliver(AuditSourceEvidenceOutboxEntry entry) {
        try {
            deliveryClient.deliver(entry);
            repository.markDelivered(entry.auditEventId(), clock.instant());
        } catch (RestClientResponseException exception) {
            String errorCode = "RLC_HTTP_" + exception.getStatusCode().value();
            if (exception.getStatusCode().is4xxClientError()) {
                repository.requireReconciliation(entry.auditEventId(), errorCode);
                log.error("Audit source evidence requires reconciliation eventId={} errorCode={}",
                        entry.auditEventId(), errorCode);
                return;
            }
            defer(entry, errorCode);
        } catch (ResourceAccessException | ResponseStatusException exception) {
            defer(entry, exception.getClass().getSimpleName());
        } catch (IllegalStateException exception) {
            repository.requireReconciliation(entry.auditEventId(), "RLC_ACKNOWLEDGEMENT_INVALID");
            log.error("Audit source evidence requires reconciliation eventId={} errorCode={}",
                    entry.auditEventId(), "RLC_ACKNOWLEDGEMENT_INVALID");
        } catch (RestClientException exception) {
            defer(entry, "RLC_CLIENT_ERROR");
        }
    }

    private void defer(AuditSourceEvidenceOutboxEntry entry, String errorCode) {
        boolean exhausted = entry.attemptCount() + 1 >= MAX_AUTOMATIC_ATTEMPTS;
        long backoffSeconds = Math.min(300, 1L << Math.min(entry.attemptCount(), 8));
        repository.defer(entry.auditEventId(), clock.instant().plusSeconds(backoffSeconds),
                errorCode, exhausted);
        if (exhausted) {
            log.error("Audit source evidence retries exhausted eventId={} errorCode={}",
                    entry.auditEventId(), errorCode);
        } else {
            log.warn("Audit source evidence delivery deferred eventId={} attempt={} errorCode={}",
                    entry.auditEventId(), entry.attemptCount() + 1, errorCode);
        }
    }
}
