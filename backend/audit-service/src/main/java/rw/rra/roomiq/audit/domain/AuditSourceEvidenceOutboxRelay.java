package rw.rra.roomiq.audit.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import rw.rra.roomiq.audit.integration.AuditSourceEvidenceDeliveryClient;
import rw.rra.roomiq.audit.integration.AuditSourceEvidenceProperties;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;

@Component
@ConditionalOnProperty(name = "roomiq.audit.source-evidence.enabled", havingValue = "true")
public class AuditSourceEvidenceOutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(AuditSourceEvidenceOutboxRelay.class);
    private static final Duration CLAIM_LEASE = Duration.ofMinutes(10);
    private static final int BATCH_SIZE = 25;

    private final AuditSourceEvidenceOutboxRepository repository;
    private final AuditSourceEvidenceOutboxTransactions transactions;
    private final AuditSourceEvidenceProperties properties;
    private final AuditSourceEvidenceDeliveryClient deliveryClient;
    private final AuditSourceEvidenceOutboxService outboxService;
    private final Clock clock;

    @Autowired
    public AuditSourceEvidenceOutboxRelay(AuditSourceEvidenceOutboxRepository repository,
                                          AuditSourceEvidenceOutboxTransactions transactions,
                                          AuditSourceEvidenceProperties properties,
                                          AuditSourceEvidenceDeliveryClient deliveryClient,
                                          AuditSourceEvidenceOutboxService outboxService) {
        this(repository, transactions, properties, deliveryClient, outboxService, Clock.systemUTC());
    }

    AuditSourceEvidenceOutboxRelay(AuditSourceEvidenceOutboxRepository repository,
                                   AuditSourceEvidenceOutboxTransactions transactions,
                                   AuditSourceEvidenceProperties properties,
                                   AuditSourceEvidenceDeliveryClient deliveryClient,
                                   AuditSourceEvidenceOutboxService outboxService, Clock clock) {
        this.repository = repository;
        this.transactions = transactions;
        this.properties = properties;
        this.deliveryClient = deliveryClient;
        this.outboxService = outboxService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${roomiq.audit.source-evidence.relay-fixed-delay-ms:1000}")
    public void deliverPendingEvidence() {
        if (!properties.isEnabled()) {
            return;
        }
        reconcileConfigurationBlockedRows();
        Instant now = clock.instant();
        for (AuditSourceEvidenceOutboxEntry entry : transactions.claimDue(
                now, now.plus(CLAIM_LEASE), BATCH_SIZE)) {
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
            persistOutcome(entry, "RLC_OUTBOX_OUTCOME_PERSIST_FAILED",
                    () -> transactions.markDelivered(entry.auditEventId(), entry.claimToken(), clock.instant()));
        } catch (RestClientResponseException exception) {
            String errorCode = "RLC_HTTP_" + exception.getStatusCode().value();
            if (exception.getStatusCode().is4xxClientError()) {
                if (persistOutcome(entry, "RLC_OUTBOX_OUTCOME_PERSIST_FAILED",
                        () -> transactions.requireReconciliation(
                                entry.auditEventId(), entry.claimToken(), errorCode))) {
                    log.error("Audit source evidence requires reconciliation eventId={} errorCode={}",
                            entry.auditEventId(), errorCode);
                }
                return;
            }
            defer(entry, errorCode);
        } catch (ResourceAccessException | ResponseStatusException exception) {
            defer(entry, exception.getClass().getSimpleName());
        } catch (IllegalStateException exception) {
            if (persistOutcome(entry, "RLC_OUTBOX_OUTCOME_PERSIST_FAILED",
                    () -> transactions.requireReconciliation(
                            entry.auditEventId(), entry.claimToken(), "RLC_ACKNOWLEDGEMENT_INVALID"))) {
                log.error("Audit source evidence requires reconciliation eventId={} errorCode={}",
                        entry.auditEventId(), "RLC_ACKNOWLEDGEMENT_INVALID");
            }
        } catch (RestClientException exception) {
            defer(entry, "RLC_CLIENT_ERROR");
        } catch (RuntimeException exception) {
            defer(entry, "RLC_UNEXPECTED_DELIVERY_ERROR");
        }
    }

    private void defer(AuditSourceEvidenceOutboxEntry entry, String errorCode) {
        boolean exhausted = entry.attemptCount() + 1
                >= AuditSourceEvidenceOutboxTransactions.MAX_AUTOMATIC_ATTEMPTS;
        long backoffSeconds = Math.min(300, 1L << Math.min(entry.attemptCount(), 8));
        boolean persisted = persistOutcome(entry, "RLC_OUTBOX_OUTCOME_PERSIST_FAILED",
                () -> transactions.defer(entry.auditEventId(), entry.claimToken(),
                        clock.instant().plusSeconds(backoffSeconds), errorCode, exhausted));
        if (!persisted) {
            return;
        }
        if (exhausted) {
            log.error("Audit source evidence retries exhausted eventId={} errorCode={}",
                    entry.auditEventId(), errorCode);
        } else {
            log.warn("Audit source evidence delivery deferred eventId={} attempt={} errorCode={}",
                    entry.auditEventId(), entry.attemptCount() + 1, errorCode);
        }
    }

    private boolean persistOutcome(
            AuditSourceEvidenceOutboxEntry entry, String persistenceErrorCode, BooleanSupplier persist) {
        try {
            if (!persist.getAsBoolean()) {
                log.warn("Audit source evidence claim no longer owned eventId={} errorCode={}",
                        entry.auditEventId(), "RLC_OUTBOX_CLAIM_LOST");
                return false;
            }
            return true;
        } catch (RuntimeException exception) {
            log.error("Audit source evidence outcome persistence failed eventId={} errorCode={}",
                    entry.auditEventId(), persistenceErrorCode);
            return false;
        }
    }
}
