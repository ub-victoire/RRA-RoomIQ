package rw.rra.roomiq.lifecycle.retention;

import rw.rra.roomiq.lifecycle.holdcontrol.RecordsLegalHold;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

public record RecordsHoldEvaluationProvenance(
        UUID holdId,
        String status,
        long version,
        UUID createdByUserId,
        Instant createdAt,
        String instructionReferenceSha256,
        UUID releaseRequestedByUserId,
        Instant releaseRequestedAt,
        String releaseReferenceSha256,
        UUID verifiedByUserId,
        Instant verifiedAt,
        String verificationReferenceSha256) {

    public static RecordsHoldEvaluationProvenance from(RecordsLegalHold hold) {
        return new RecordsHoldEvaluationProvenance(
                hold.holdId(), hold.status().name(), hold.version(), hold.createdByUserId(), hold.createdAt(),
                digest(hold.instructionReference()), hold.releaseRequestedByUserId(), hold.releaseRequestedAt(),
                digest(hold.releaseReference()), hold.verifiedByUserId(), hold.verifiedAt(),
                digest(hold.verificationReference()));
    }

    private static String digest(String value) {
        if (value == null) {
            return null;
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
