package rw.rra.roomiq.booking.integration;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import rw.rra.roomiq.booking.domain.repository.BookingSourceEvidenceOutboxEntry;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;

@Component
public class BookingSourceEvidenceDeliveryClient {
    private static final String INGESTION_PATH = "/internal/v1/source-evidence/booking-requests";

    private final RestClient restClient;
    private final BookingSourceEvidenceTokenProvider tokenProvider;

    public BookingSourceEvidenceDeliveryClient(BookingSourceEvidenceProperties properties,
                                               BookingSourceEvidenceTokenProvider tokenProvider) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = RestClient.builder()
                .baseUrl(properties.getRlcUrl())
                .requestFactory(requestFactory)
                .build();
        this.tokenProvider = tokenProvider;
    }

    public DeliveryAcknowledgement deliver(BookingSourceEvidenceOutboxEntry entry) {
        var response = restClient.post()
                .uri(INGESTION_PATH)
                .header("Authorization", tokenProvider.createAuthorizationHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .body(toEvidence(entry))
                .retrieve()
                .body(DeliveryAcknowledgement.class);
        if (response == null || !entry.eventId().equals(response.eventId())
                || entry.sourceRevision() != response.sourceRevision()
                || !("ACCEPTED".equals(response.outcome()) || "DUPLICATE".equals(response.outcome()))) {
            throw new IllegalStateException("RLC did not confirm durable acceptance of Booking evidence");
        }
        return response;
    }

    private static BookingSourceEvidence toEvidence(BookingSourceEvidenceOutboxEntry entry) {
        return new BookingSourceEvidence(1, "booking-service", "BOOKING_REQUEST",
                entry.bookingRequestId().toString(), entry.eventId(),
                "BOOKING_REQUEST_STATUS_CHANGED", 1, entry.sourceRevision(), entry.categoryCode(),
                entry.recordCreatedAt(), entry.occurredAt(), entry.authoritativeStartAt(),
                entry.startBasisCode(), entry.closedAt(), entry.decisionAt(), null, null,
                entry.correlationId(), null, entry.supersedesEventId(),
                entry.currentLifecycleStatus(), entry.observedAt());
    }

    public record DeliveryAcknowledgement(UUID eventId, long sourceRevision, String outcome) {
    }
}
