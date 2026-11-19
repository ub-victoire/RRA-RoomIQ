package rw.rra.roomiq.audit.domain;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class AuditSourceEvidenceOutboxTransactions {
    static final int MAX_AUTOMATIC_ATTEMPTS = 10;

    private final AuditSourceEvidenceOutboxRepository repository;

    public AuditSourceEvidenceOutboxTransactions(AuditSourceEvidenceOutboxRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<AuditSourceEvidenceOutboxEntry> claimDue(
            Instant now, Instant leaseExpiresAt, int limit) {
        return repository.claimDue(now, leaseExpiresAt, limit, UUID.randomUUID());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markDelivered(UUID eventId, UUID claimToken, Instant deliveredAt) {
        return repository.markDelivered(eventId, claimToken, deliveredAt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean defer(
            UUID eventId, UUID claimToken, Instant retryAt, String errorCode, boolean exhausted) {
        return repository.defer(eventId, claimToken, retryAt, errorCode, exhausted);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean requireReconciliation(UUID eventId, UUID claimToken, String errorCode) {
        return repository.requireReconciliation(eventId, claimToken, errorCode);
    }
}
