package rw.rra.roomiq.lifecycle.legalhold.api;

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
import rw.rra.roomiq.lifecycle.legalhold.LegalHoldRecord;
import rw.rra.roomiq.lifecycle.legalhold.LegalHoldService;
import rw.rra.roomiq.lifecycle.security.LifecycleIdentityAuthorizationFilter;

import java.util.UUID;

@RestController
@Validated
@RequestMapping("/api/v1/legal-holds")
public class LegalHoldController {
    private final LegalHoldService service;

    public LegalHoldController(LegalHoldService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<LegalHoldResponse> create(
            @Valid @RequestBody CreateLegalHoldRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            HttpServletRequest httpRequest) {
        LegalHoldRecord result = service.create(request, actor(httpRequest), idempotencyKey,
                CorrelationIdFilter.resolveCorrelationId(httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED).body(LegalHoldResponse.from(result));
    }

    @GetMapping("/{holdId}")
    public LegalHoldResponse get(@PathVariable UUID holdId) {
        return LegalHoldResponse.from(service.get(holdId));
    }

    @PostMapping("/{holdId}/submit")
    public LegalHoldResponse submit(@PathVariable UUID holdId,
                                    @RequestHeader("If-Match") long expectedVersion,
                                    @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128)
                                    String idempotencyKey, HttpServletRequest httpRequest) {
        return respond(service.submit(holdId, expectedVersion, actor(httpRequest), idempotencyKey,
                CorrelationIdFilter.resolveCorrelationId(httpRequest)));
    }

    @PostMapping("/{holdId}/approve")
    public LegalHoldResponse approve(@PathVariable UUID holdId,
                                     @RequestHeader("If-Match") long expectedVersion,
                                     @Valid @RequestBody LegalHoldEvidenceRequest request,
                                     @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128)
                                     String idempotencyKey, HttpServletRequest httpRequest) {
        return respond(service.approve(holdId, expectedVersion, actor(httpRequest), request, idempotencyKey,
                CorrelationIdFilter.resolveCorrelationId(httpRequest)));
    }

    @PostMapping("/{holdId}/reject")
    public LegalHoldResponse reject(@PathVariable UUID holdId,
                                    @RequestHeader("If-Match") long expectedVersion,
                                    @Valid @RequestBody LegalHoldEvidenceRequest request,
                                    @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128)
                                    String idempotencyKey, HttpServletRequest httpRequest) {
        return respond(service.reject(holdId, expectedVersion, actor(httpRequest), request, idempotencyKey,
                CorrelationIdFilter.resolveCorrelationId(httpRequest)));
    }

    @PutMapping("/{holdId}/amend")
    public LegalHoldResponse amend(@PathVariable UUID holdId,
                                  @RequestHeader("If-Match") long expectedVersion,
                                  @Valid @RequestBody AmendLegalHoldRequest request,
                                  @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128)
                                  String idempotencyKey, HttpServletRequest httpRequest) {
        return respond(service.amend(holdId, expectedVersion, actor(httpRequest), request, idempotencyKey,
                CorrelationIdFilter.resolveCorrelationId(httpRequest)));
    }

    @PostMapping("/{holdId}/request-release")
    public LegalHoldResponse requestRelease(@PathVariable UUID holdId,
                                            @RequestHeader("If-Match") long expectedVersion,
                                            @Valid @RequestBody LegalHoldEvidenceRequest request,
                                            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128)
                                            String idempotencyKey, HttpServletRequest httpRequest) {
        return respond(service.requestRelease(holdId, expectedVersion, actor(httpRequest), request,
                idempotencyKey, CorrelationIdFilter.resolveCorrelationId(httpRequest)));
    }

    @PostMapping("/{holdId}/release")
    public LegalHoldResponse release(@PathVariable UUID holdId,
                                     @RequestHeader("If-Match") long expectedVersion,
                                     @Valid @RequestBody LegalHoldEvidenceRequest request,
                                     @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128)
                                     String idempotencyKey, HttpServletRequest httpRequest) {
        return respond(service.approveRelease(holdId, expectedVersion, actor(httpRequest), request,
                idempotencyKey, CorrelationIdFilter.resolveCorrelationId(httpRequest)));
    }

    private static LegalHoldResponse respond(LegalHoldRecord result) {
        return LegalHoldResponse.from(result);
    }

    private static UUID actor(HttpServletRequest request) {
        Object value = request.getAttribute(LifecycleIdentityAuthorizationFilter.ACTOR_ATTRIBUTE);
        if (value instanceof UUID actorId) {
            return actorId;
        }
        throw new IllegalStateException("Trusted Identity authorization actor is missing");
    }
}
