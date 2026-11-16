package rw.rra.roomiq.lifecycle.holdcontrol;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import jakarta.annotation.PostConstruct;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import rw.rra.roomiq.lifecycle.security.RecordsHoldServiceTokenProvider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
public class RecordsHoldInstructionRelay {
    private static final Logger logger = LoggerFactory.getLogger(RecordsHoldInstructionRelay.class);
    private static final String ENDPOINT = "/internal/v1/legal-holds/instructions";

    private final RecordsHoldInstructionOutboxRepository outbox;
    private final RecordsHoldAcknowledgementService acknowledgements;
    private final RecordsHoldServiceTokenProvider tokenProvider;
    private final Clock clock;
    private final Duration leaseDuration;
    private final int batchSize;
    private final int maxAttempts;
    private final boolean enabled;

    @Autowired
    public RecordsHoldInstructionRelay(
            RecordsHoldInstructionOutboxRepository outbox,
            RecordsHoldAcknowledgementService acknowledgements,
            RecordsHoldServiceTokenProvider tokenProvider,
            @Value("${roomiq.lifecycle.booking-hold-instructions.enabled:true}") boolean enabled,
            @Value("${roomiq.lifecycle.booking-hold-instructions.lease-duration:PT30S}") Duration leaseDuration,
            @Value("${roomiq.lifecycle.booking-hold-instructions.batch-size:20}") int batchSize,
            @Value("${roomiq.lifecycle.booking-hold-instructions.max-attempts:12}") int maxAttempts) {
        this(outbox, acknowledgements, tokenProvider, enabled, leaseDuration, batchSize, maxAttempts,
                Clock.systemUTC());
    }

    RecordsHoldInstructionRelay(RecordsHoldInstructionOutboxRepository outbox,
                               RecordsHoldAcknowledgementService acknowledgements,
                               RecordsHoldServiceTokenProvider tokenProvider,
                               boolean enabled,
                               Duration leaseDuration, int batchSize, int maxAttempts, Clock clock) {
        if (leaseDuration.isNegative() || leaseDuration.isZero() || batchSize < 1 || maxAttempts < 1) {
            throw new IllegalArgumentException("Invalid hold instruction relay limits");
        }
        this.outbox = outbox;
        this.acknowledgements = acknowledgements;
        this.tokenProvider = tokenProvider;
        this.enabled = enabled;
        this.leaseDuration = leaseDuration;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${roomiq.lifecycle.booking-hold-instructions.delivery-interval:PT10S}")
    public void deliverDueInstructions() {
        if (!enabled || !tokenProvider.isConfigured()) {
            return;
        }
        Instant now = clock.instant();
        List<RecordsHoldInstruction> claimed;
        try {
            claimed = outbox.claimDue(now, now.plus(leaseDuration), batchSize);
        } catch (RuntimeException exception) {
            logger.error("Could not claim due Booking legal-hold instructions", exception);
            return;
        }
        for (RecordsHoldInstruction instruction : claimed) {
            deliver(instruction);
        }
    }

    @PostConstruct
    void reportMissingSenderConfiguration() {
        if (enabled && !tokenProvider.isConfigured()) {
            logger.warn("Booking hold delivery is disabled until BOOKING_SERVICE_URL and RLC service-token "
                    + "issuer, key ID, and private-key location are configured");
        }
    }

    private void deliver(RecordsHoldInstruction instruction) {
        try {
            BookingHoldAcknowledgement acknowledgement = tokenProvider.bookingClient().post()
                    .uri(ENDPOINT)
                    .header(HttpHeaders.AUTHORIZATION, tokenProvider.createAuthorizationHeader())
                    .body(instruction.instruction())
                    .retrieve()
                    .body(BookingHoldAcknowledgement.class);
            acknowledgements.accept(instruction, acknowledgement);
        } catch (HttpClientErrorException exception) {
            outbox.reconcile(instruction, null, "BOOKING_HTTP_" + exception.getStatusCode().value());
            logger.warn("Booking rejected hold instruction {} with status {}",
                    instruction.instruction().instructionId(), exception.getStatusCode());
        } catch (HttpServerErrorException | ResourceAccessException exception) {
            retry(instruction, "BOOKING_UNAVAILABLE");
        } catch (RestClientException exception) {
            retry(instruction, "DELIVERY_FAILED");
            logger.warn("Booking hold instruction delivery failed for {}",
                    instruction.instruction().instructionId(), exception);
        } catch (RuntimeException exception) {
            retry(instruction, "DELIVERY_FAILED");
            logger.warn("Booking hold instruction delivery failed for {}",
                    instruction.instruction().instructionId(), exception);
        }
    }

    private void retry(RecordsHoldInstruction instruction, String errorCode) {
        try {
            outbox.retry(instruction, clock.instant(), errorCode, maxAttempts);
        } catch (RuntimeException exception) {
            logger.error("Could not record Booking hold-instruction delivery failure for {}",
                    instruction.instruction().instructionId(), exception);
        }
    }
}
