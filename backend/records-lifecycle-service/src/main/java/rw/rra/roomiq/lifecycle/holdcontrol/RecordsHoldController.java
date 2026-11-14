package rw.rra.roomiq.lifecycle.holdcontrol;

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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.rra.roomiq.common.web.CorrelationIdFilter;
import rw.rra.roomiq.lifecycle.security.LifecycleIdentityAuthorizationFilter;

import java.util.UUID;

@RestController
@Validated
@RequestMapping("/api/v1/legal-holds")
public class RecordsHoldController {
    private final RecordsHoldRegistryService service;

    public RecordsHoldController(RecordsHoldRegistryService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<RecordsHoldResponse> create(
            @Valid @RequestBody CreateRecordsHoldRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            HttpServletRequest httpRequest) {
        RecordsLegalHold hold = service.create(request, actor(httpRequest), idempotencyKey,
                CorrelationIdFilter.resolveCorrelationId(httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED).body(RecordsHoldResponse.from(hold));
    }

    @GetMapping("/{holdId}")
    public RecordsHoldResponse get(@PathVariable UUID holdId) {
        return RecordsHoldResponse.from(service.get(holdId));
    }

    @PostMapping("/{holdId}/release-requests")
    public RecordsHoldResponse requestRelease(
            @PathVariable UUID holdId,
            @Valid @RequestBody ReleaseRecordsHoldRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            HttpServletRequest httpRequest) {
        RecordsLegalHold hold = service.requestRelease(holdId, request, actor(httpRequest), idempotencyKey,
                CorrelationIdFilter.resolveCorrelationId(httpRequest));
        return RecordsHoldResponse.from(hold);
    }

    @PostMapping("/{holdId}/release-verification")
    public RecordsHoldResponse verifyRelease(
            @PathVariable UUID holdId,
            @Valid @RequestBody VerifyRecordsHoldReleaseRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            HttpServletRequest httpRequest) {
        RecordsLegalHold hold = service.verifyRelease(holdId, request, actor(httpRequest), idempotencyKey,
                CorrelationIdFilter.resolveCorrelationId(httpRequest));
        return RecordsHoldResponse.from(hold);
    }

    private static UUID actor(HttpServletRequest request) {
        Object value = request.getAttribute(LifecycleIdentityAuthorizationFilter.ACTOR_ATTRIBUTE);
        if (value instanceof UUID actorId) {
            return actorId;
        }
        throw new IllegalStateException("Trusted Identity authorization actor is missing");
    }
}
