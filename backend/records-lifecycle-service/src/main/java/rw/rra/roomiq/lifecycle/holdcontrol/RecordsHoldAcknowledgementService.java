package rw.rra.roomiq.lifecycle.holdcontrol;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;

@Service
public class RecordsHoldAcknowledgementService {
    private final RecordsHoldInstructionOutboxRepository outbox;
    private final RecordsHoldRepository holds;
    private final RecordsHoldEventWriter events;
    private final Clock clock;

    @Autowired
    public RecordsHoldAcknowledgementService(RecordsHoldInstructionOutboxRepository outbox,
                                             RecordsHoldRepository holds,
                                             RecordsHoldEventWriter events) {
        this(outbox, holds, events, Clock.systemUTC());
    }

    RecordsHoldAcknowledgementService(RecordsHoldInstructionOutboxRepository outbox,
                                      RecordsHoldRepository holds,
                                      RecordsHoldEventWriter events, Clock clock) {
        this.outbox = outbox;
        this.holds = holds;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public void accept(RecordsHoldInstruction claimed, BookingHoldAcknowledgement acknowledgement) {
        RecordsHoldInstruction instruction = outbox.findForUpdate(claimed.outboxId());
        if (!"PROCESSING".equals(instruction.deliveryStatus())) {
            return;
        }
        BookingHoldInstruction payload = instruction.instruction();
        RecordsLegalHold hold = holds.findByIdForUpdate(payload.holdId()).orElse(null);
        if (hold == null || !expectedAcknowledgement(payload, acknowledgement)
                || "RELEASE".equals(payload.operation())
                && hold.status() != RecordsHoldStatus.RELEASE_ENFORCEMENT_PENDING) {
            outbox.acknowledge(instruction, acknowledgement, clock.instant());
            return;
        }

        String nextStatus = "RELEASE".equals(payload.operation())
                ? RecordsHoldStatus.RELEASED.name() : hold.status().name();
        String eventType = "RELEASE".equals(payload.operation())
                ? "HOLD_OWNER_RELEASE_ACKNOWLEDGED" : "HOLD_OWNER_INSTRUCTION_ACKNOWLEDGED";
        String reference = payload.instructionId() + ":"
                + acknowledgement.outcome().name() + ":"
                + acknowledgement.bookingRequestVersion();
        String requestHash = sha256(payload.instructionId() + "\n"
                + acknowledgement.outcome() + "\n" + acknowledgement.enforcementStatus() + "\n"
                + acknowledgement.bookingRequestVersion());
        outbox.acknowledge(instruction, acknowledgement, clock.instant());
        events.appendService("booking-service", eventType, payload.instructionId().toString(),
                requestHash, payload.holdId(), hold.status().name(), nextStatus, reference,
                payload.correlationId(), clock.instant());
        if ("RELEASE".equals(payload.operation())) {
            holds.acknowledgeRelease(hold);
        }
    }

    private static boolean expectedAcknowledgement(
            BookingHoldInstruction instruction, BookingHoldAcknowledgement acknowledgement) {
        if (acknowledgement == null
                || !instruction.instructionId().equals(acknowledgement.instructionId())
                || !instruction.holdId().equals(acknowledgement.holdId())
                || instruction.instructionRevision() != acknowledgement.instructionRevision()
                || !(acknowledgement.outcome() == BookingHoldAcknowledgement.Outcome.ACCEPTED
                    || acknowledgement.outcome() == BookingHoldAcknowledgement.Outcome.DUPLICATE)) {
            return false;
        }
        String expectedState = "APPLY".equals(instruction.operation()) ? "ACTIVE" : "RELEASED";
        return expectedState.equals(acknowledgement.enforcementStatus());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
