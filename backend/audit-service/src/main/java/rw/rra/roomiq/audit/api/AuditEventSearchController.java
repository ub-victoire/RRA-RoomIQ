package rw.rra.roomiq.audit.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import rw.rra.roomiq.audit.config.AuditIdentityAuthorizationFilter;
import rw.rra.roomiq.audit.domain.AuditEvent;
import rw.rra.roomiq.audit.domain.AuditEventSearchCriteria;
import rw.rra.roomiq.audit.domain.AuditEventSearchService;
import rw.rra.roomiq.audit.integration.AuditSearchAuthorizationDecision;
import rw.rra.roomiq.common.web.ApiResponse;
import rw.rra.roomiq.common.web.DomainException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/audit-events")
@Tag(name = "Audit")
@SecurityRequirement(name = "bearerAuth")
public class AuditEventSearchController {
    private static final Logger log = LoggerFactory.getLogger(AuditEventSearchController.class);

    private final AuditEventSearchService searchService;

    public AuditEventSearchController(AuditEventSearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping
    @Operation(summary = "Search Audit events",
            description = "Searches Audit events using exact-match filters, offset-aware time bounds, and database pagination. "
                    + "Requires Identity authorization with AUDIT_READ. Building-scoped access is always restricted "
                    + "to the trusted building IDs returned by Identity.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "Audit events retrieved"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Invalid query parameter", content = @Content(schema = @Schema(implementation = rw.rra.roomiq.common.web.ApiError.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "Authentication is required or invalid",
                    content = @Content(schema = @Schema(implementation = rw.rra.roomiq.common.web.ApiError.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "Identity denied access or buildingId is outside trusted scope",
                    content = @Content(schema = @Schema(implementation = rw.rra.roomiq.common.web.ApiError.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503",
                    description = "Identity authorization is unavailable or unusable",
                    content = @Content(schema = @Schema(implementation = rw.rra.roomiq.common.web.ApiError.class)))
    })
    public ApiResponse<AuditEventSearchPageResponse> search(
            HttpServletRequest request,
            @Parameter(description = "Exact action match", in = ParameterIn.QUERY)
            @RequestParam(required = false) String action,
            @Parameter(description = "Exact resource type match", in = ParameterIn.QUERY)
            @RequestParam(required = false) String resourceType,
            @Parameter(description = "Exact resource UUID match", in = ParameterIn.QUERY)
            @RequestParam(required = false) UUID resourceId,
            @Parameter(description = "Exact actor user UUID match; does not affect authorization", in = ParameterIn.QUERY)
            @RequestParam(required = false) UUID actorUserId,
            @Parameter(description = "Exact outcome match", in = ParameterIn.QUERY)
            @RequestParam(required = false) String outcome,
            @Parameter(description = "Exact correlation ID match", in = ParameterIn.QUERY)
            @RequestParam(required = false) String correlationId,
            @Parameter(description = "Optional narrowing building UUID; cannot expand trusted scope", in = ParameterIn.QUERY)
            @RequestParam(required = false) UUID buildingId,
            @Parameter(description = "Inclusive ISO-8601 timestamp with explicit UTC offset",
                    schema = @Schema(type = "string", format = "date-time", example = "2026-01-01T00:00:00Z"))
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            OffsetDateTime occurredFrom,
            @Parameter(description = "Exclusive ISO-8601 timestamp with explicit UTC offset",
                    schema = @Schema(type = "string", format = "date-time", example = "2026-02-01T00:00:00+02:00"))
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            OffsetDateTime occurredTo,
            @Parameter(description = "Zero-based page index (default 0)",
                    schema = @Schema(type = "integer", minimum = "0", defaultValue = "0"))
            @RequestParam(required = false) String page,
            @Parameter(description = "Page size (default 20, maximum 100)",
                    schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "20"))
            @RequestParam(required = false) String size,
            @Parameter(description = "Sort field allowlist: id, actorUserId, action, resourceType, resourceId, buildingId, outcome, correlationId, occurredAt",
                    schema = @Schema(type = "string", defaultValue = "occurredAt"))
            @RequestParam(required = false, defaultValue = "occurredAt") String sortBy,
            @Parameter(description = "Sort direction: ASC or DESC (default DESC)",
                    schema = @Schema(type = "string", allowableValues = {"ASC", "DESC"}, defaultValue = "DESC"))
            @RequestParam(required = false, defaultValue = "DESC") String sortDirection) {
        AuditSearchAuthorizationDecision decision = trustedDecision(request);
        AuditEventSearchCriteria criteria = new AuditEventSearchCriteria(action, resourceType, resourceId,
                actorUserId, outcome, correlationId, buildingId,
                toInstant(occurredFrom), toInstant(occurredTo), parseInteger(page, "page"),
                parseInteger(size, "size"), sortBy, sortDirection);

        Page<AuditEvent> result = searchService.search(criteria, decision);
        AuditEventSearchPageResponse data = new AuditEventSearchPageResponse(
                result.getContent().stream().map(AuditEventSearchController::toItem).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages(),
                criteria.sortBy(), criteria.sortDirection());

        log.info("audit_search_completed page={} size={} resultCount={} correlationId={}",
                criteria.page(), criteria.size(), data.totalElements(), MDC.get("correlationId"));
        return ApiResponse.success("Audit events retrieved", data);
    }

    private static Integer parseInteger(String value, String parameterName) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException exception) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                    "Query parameter " + parameterName + " must be a valid integer");
        }
    }

    private static AuditSearchAuthorizationDecision trustedDecision(HttpServletRequest request) {
        Object decision = request.getAttribute(AuditIdentityAuthorizationFilter.AUTHORIZATION_ATTRIBUTE);
        if (decision instanceof AuditSearchAuthorizationDecision trustedDecision) {
            return trustedDecision;
        }
        throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_AUTHORIZATION_UNAVAILABLE",
                "Identity authorization is unavailable; the request cannot proceed");
    }

    private static Instant toInstant(OffsetDateTime dateTime) {
        return dateTime == null ? null : dateTime.toInstant();
    }

    private static AuditEventSearchItemResponse toItem(AuditEvent event) {
        return new AuditEventSearchItemResponse(event.getId(), event.getActorUserId(), event.getAction(),
                event.getResourceType(), event.getResourceId(), event.getScopeOfficeBuildingId(), event.getOutcome(),
                event.getCorrelationId(), event.getOccurredAt());
    }
}
