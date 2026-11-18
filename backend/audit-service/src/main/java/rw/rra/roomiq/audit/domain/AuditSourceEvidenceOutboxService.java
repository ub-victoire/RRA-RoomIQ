package rw.rra.roomiq.audit.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import rw.rra.roomiq.audit.integration.AuditSourceEvidenceProperties;

import java.time.Clock;
import java.time.Instant;

@Service
public class AuditSourceEvidenceOutboxService {
    private static final Logger log = LoggerFactory.getLogger(AuditSourceEvidenceOutboxService.class);

    private final AuditSourceEvidenceOutboxRepository repository;
    private final AuditSourceEvidenceProperties properties;
    private final Clock clock;

    @Autowired
    public AuditSourceEvidenceOutboxService(AuditSourceEvidenceOutboxRepository repository,
                                            AuditSourceEvidenceProperties properties) {
        this(repository, properties, Clock.systemUTC());
    }

    AuditSourceEvidenceOutboxService(AuditSourceEvidenceOutboxRepository repository,
                                     AuditSourceEvidenceProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditEvent event) {
        if (event == null || event.getId() == null || event.getAction() == null
                || event.getIngestedAt() == null || event.getOccurredAt() == null) {
            throw new IllegalArgumentException("Audit evidence requires a persisted event and source timestamps");
        }
        Instant observedAt = clock.instant();
        AuditSourceEvidenceProperties.Mapping mapping = properties.mappingFor(event.getAction());
        if (mapping == null) {
            String errorCode = properties.mappingDiagnostic() == null
                    ? "AUDIT_EVENT_CATEGORY_MAPPING_MISSING" : "AUDIT_EVENT_CATEGORY_MAPPING_INVALID";
            repository.insert(event, null, null, observedAt, "CONFIGURATION_BLOCKED", errorCode);
            log.warn("Audit source evidence is blocked eventId={} action={} errorCode={} "
                            + "configure an explicit event-to-category and start-basis mapping",
                    event.getId(), event.getAction(), errorCode);
            return;
        }
        repository.insert(event, mapping.categoryCode(), mapping.startBasisCode(), observedAt,
                "PENDING", null);
    }

    public void applyCurrentMapping(AuditSourceEvidenceOutboxEntry entry) {
        AuditSourceEvidenceProperties.Mapping mapping = properties.mappingFor(entry.action());
        if (mapping == null) {
            String errorCode = properties.mappingDiagnostic() == null
                    ? "AUDIT_EVENT_CATEGORY_MAPPING_MISSING" : "AUDIT_EVENT_CATEGORY_MAPPING_INVALID";
            if (repository.recordConfigurationBlock(entry.auditEventId(), errorCode)) {
                log.warn("Audit source evidence remains blocked eventId={} action={} errorCode={} "
                                + "configure an explicit event-to-category and start-basis mapping",
                        entry.auditEventId(), entry.action(), errorCode);
            }
            return;
        }
        repository.applyMapping(entry.auditEventId(), mapping.categoryCode(), mapping.startBasisCode());
    }
}
