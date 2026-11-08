package rw.rra.roomiq.lifecycle.retention;

import rw.rra.roomiq.lifecycle.policy.PolicyStatus;

import java.time.Instant;
import java.util.UUID;

public record PolicyVersionSnapshot(
        UUID id,
        String version,
        PolicyStatus status,
        Instant effectiveFrom,
        Instant effectiveTo) {
}
