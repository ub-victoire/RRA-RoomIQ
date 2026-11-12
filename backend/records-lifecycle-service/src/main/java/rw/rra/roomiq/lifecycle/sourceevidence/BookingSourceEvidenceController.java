package rw.rra.roomiq.lifecycle.sourceevidence;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.rra.roomiq.common.web.DomainException;

@RestController
@RequestMapping("/internal/v1/source-evidence/booking-requests")
public class BookingSourceEvidenceController {
    private final SourceOwnerEvidenceIngestionService ingestionService;

    public BookingSourceEvidenceController(SourceOwnerEvidenceIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping
    public ResponseEntity<SourceOwnerEvidenceIngestionResult> ingest(
            JwtAuthenticationToken authenticatedBookingService,
            @RequestBody SourceOwnerEvidence evidence) {
        SourceOwnerEvidenceIngestionResult result;
        try {
            result = ingestionService.ingest(authenticatedBookingService.getToken().getSubject(), evidence);
        } catch (SourceOwnerEvidenceValidationException exception) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "SOURCE_EVIDENCE_INVALID", exception.getMessage());
        }
        return switch (result.outcome()) {
            case ACCEPTED -> ResponseEntity.status(HttpStatus.CREATED).body(result);
            case DUPLICATE -> ResponseEntity.ok(result);
            case STALE, CONFLICTED -> ResponseEntity.status(HttpStatus.CONFLICT).body(result);
        };
    }
}
