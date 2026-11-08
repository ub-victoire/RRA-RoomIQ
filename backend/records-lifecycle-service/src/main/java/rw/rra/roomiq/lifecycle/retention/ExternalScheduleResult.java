package rw.rra.roomiq.lifecycle.retention;

import java.time.Instant;

public record ExternalScheduleResult(String scheduleReference, Instant expiryAt) {
}
