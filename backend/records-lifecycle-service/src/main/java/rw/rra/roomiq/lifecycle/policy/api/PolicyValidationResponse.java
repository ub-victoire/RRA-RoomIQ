package rw.rra.roomiq.lifecycle.policy.api;

import java.util.List;
import java.util.UUID;

public record PolicyValidationResponse(UUID policyVersionId, boolean valid, List<String> errors) {
}
