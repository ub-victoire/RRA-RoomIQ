package rw.rra.roomiq.lifecycle.policy.api;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record SupersedePolicyRequest(@NotNull Instant effectiveTo) {
}
