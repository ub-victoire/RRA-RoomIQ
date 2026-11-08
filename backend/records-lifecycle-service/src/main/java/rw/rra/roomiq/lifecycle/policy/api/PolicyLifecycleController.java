package rw.rra.roomiq.lifecycle.policy.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.rra.roomiq.common.web.CorrelationIdFilter;
import rw.rra.roomiq.lifecycle.policy.PolicyLifecycleService;
import rw.rra.roomiq.lifecycle.security.LifecycleIdentityAuthorizationFilter;

import java.util.UUID;

@RestController
@Validated
@RequestMapping("/api/v1/retention/policies")
public class PolicyLifecycleController {
    private final PolicyLifecycleService service;

    public PolicyLifecycleController(PolicyLifecycleService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<PolicyResponse> create(
            @Valid @RequestBody CreatePolicyRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            HttpServletRequest httpRequest) {
        PolicyResponse response = service.create(request, actor(httpRequest), idempotencyKey,
                CorrelationIdFilter.resolveCorrelationId(httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{policyId}")
    public PolicyResponse get(@PathVariable UUID policyId) {
        return service.get(policyId);
    }

    @PostMapping("/{policyId}/rules")
    public PolicyRuleResponse createRule(
            @PathVariable UUID policyId,
            @Valid @RequestBody PolicyRuleRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            HttpServletRequest httpRequest) {
        return service.createRule(policyId, request, actor(httpRequest), idempotencyKey,
                CorrelationIdFilter.resolveCorrelationId(httpRequest));
    }

    @PutMapping("/{policyId}/rules/{ruleId}")
    public PolicyRuleResponse updateRule(
            @PathVariable UUID policyId,
            @PathVariable UUID ruleId,
            @Valid @RequestBody PolicyRuleRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            HttpServletRequest httpRequest) {
        return service.updateRule(policyId, ruleId, request, actor(httpRequest), idempotencyKey,
                CorrelationIdFilter.resolveCorrelationId(httpRequest));
    }

    @PostMapping("/{policyId}/validate")
    public PolicyValidationResponse validate(
            @PathVariable UUID policyId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            HttpServletRequest httpRequest) {
        return service.validate(policyId, actor(httpRequest), idempotencyKey,
                CorrelationIdFilter.resolveCorrelationId(httpRequest));
    }

    @PostMapping("/{policyId}/activate")
    public PolicyResponse activate(
            @PathVariable UUID policyId,
            @Valid @RequestBody ActivatePolicyRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            HttpServletRequest httpRequest) {
        return service.activate(policyId, request, actor(httpRequest), idempotencyKey,
                CorrelationIdFilter.resolveCorrelationId(httpRequest));
    }

    @PostMapping("/{policyId}/supersede")
    public PolicyResponse supersede(
            @PathVariable UUID policyId,
            @Valid @RequestBody SupersedePolicyRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            HttpServletRequest httpRequest) {
        return service.supersede(policyId, request, actor(httpRequest), idempotencyKey,
                CorrelationIdFilter.resolveCorrelationId(httpRequest));
    }

    @PostMapping("/{policyId}/withdraw")
    public PolicyResponse withdraw(
            @PathVariable UUID policyId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            HttpServletRequest httpRequest) {
        return service.withdraw(policyId, actor(httpRequest), idempotencyKey,
                CorrelationIdFilter.resolveCorrelationId(httpRequest));
    }

    private static UUID actor(HttpServletRequest request) {
        Object value = request.getAttribute(LifecycleIdentityAuthorizationFilter.ACTOR_ATTRIBUTE);
        if (value instanceof UUID actorId) {
            return actorId;
        }
        throw new IllegalStateException("Trusted Identity authorization actor is missing");
    }
}
