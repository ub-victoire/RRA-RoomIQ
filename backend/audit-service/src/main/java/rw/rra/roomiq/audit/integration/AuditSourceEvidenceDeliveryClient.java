package rw.rra.roomiq.audit.integration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import rw.rra.roomiq.audit.domain.AuditSourceEvidenceOutboxEntry;

import java.net.http.HttpClient;
import java.time.Instant;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "roomiq.audit.source-evidence.enabled", havingValue = "true")
public class AuditSourceEvidenceDeliveryClient {
    private static final String INGESTION_PATH = "/internal/v1/source-evidence/audit-events";
    private final RestClient restClient;
    private final AuditSourceEvidenceTokenProvider tokenProvider;

    public AuditSourceEvidenceDeliveryClient(AuditSourceEvidenceProperties properties,
                                            AuditSourceEvidenceTokenProvider tokenProvider) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.rlcUrl())
                .requestFactory(requestFactory)
                .build();
        this.tokenProvider = tokenProvider;
    }

    public DeliveryAcknowledgement deliver(AuditSourceEvidenceOutboxEntry entry) {
        DeliveryAcknowledgement response = restClient.post()
                .uri(INGESTION_PATH)
                .header("Authorization", tokenProvider.createAuthorizationHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .body(toEvidence(entry))
                .retrieve()
                .body(DeliveryAcknowledgement.class);
        if (response == null || !entry.auditEventId().equals(response.eventId())
                || response.sourceRevision() != 1
                || !("ACCEPTED".equals(response.outcome()) || "DUPLICATE".equals(response.outcome()))) {
            throw new IllegalStateException("RLC did not confirm durable acceptance of Audit evidence");
        }
        return response;
    }

    private static SourceOwnerEvidence toEvidence(AuditSourceEvidenceOutboxEntry entry) {
        Instant authoritativeStartAt = switch (entry.startBasisCode()) {
            case "OCCURRED_AT", "ORIGINAL_EVENT" -> entry.occurredAt();
            case "RECORD_CREATED_AT" -> entry.recordCreatedAt();
            default -> throw new IllegalStateException("Audit source-evidence start basis is unsupported");
        };
        return new SourceOwnerEvidence(1, "audit-service", "AUDIT_EVENT",
                entry.auditEventId().toString(), entry.auditEventId(), entry.action(), 1, 1,
                entry.categoryCode(), entry.recordCreatedAt(), entry.occurredAt(), authoritativeStartAt,
                entry.startBasisCode(), null, null, null, null, entry.correlationId(), null,
                null, "RECORDED", entry.observedAt());
    }

    public record DeliveryAcknowledgement(UUID eventId, long sourceRevision, String outcome) {
    }

    public record SourceOwnerEvidence(
            int contractVersion,
            String sourceService,
            String recordType,
            String recordId,
            UUID eventId,
            String eventType,
            int eventVersion,
            long sourceRevision,
            String categoryCode,
            Instant recordCreatedAt,
            Instant occurredAt,
            Instant authoritativeStartAt,
            String startBasisCode,
            Instant closedAt,
            Instant decisionAt,
            Instant transactionClosedAt,
            String actorSubject,
            String correlationId,
            String causationId,
            UUID supersedesEventId,
            String currentLifecycleStatus,
            Instant observedAt) {
    }
}
