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
@RequestMapping("/internal/v1/source-evidence/audit-events")
public class AuditSourceEvidenceController {
    private final SourceOwnerEvidenceIngestionService ingestionService;

    public AuditSourceEvidenceController(SourceOwnerEvidenceIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping
    public ResponseEntity<SourceOwnerEvidenceIngestionResult> ingest(
            JwtAuthenticationToken authenticatedAuditService,
            @RequestBody SourceOwnerEvidence evidence) {
        SourceOwnerEvidenceIngestionResult result;
        try {
            result = ingestionService.ingest(authenticatedAuditService.getToken().getSubject(), evidence);
        } catch (SourceOwnerEvidenceValidationException exception) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "SOURCE_EVIDENCE_INVALID", exception.getMessage());
        }
        if (result.outcome() == SourceOwnerEvidenceOutcome.ACCEPTED) {
            return ResponseEntity.status(HttpStatus.CREATED).body(result);
        }
        if (result.outcome() == SourceOwnerEvidenceOutcome.DUPLICATE) {
            return ResponseEntity.ok(result);
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(result);
    }
}
