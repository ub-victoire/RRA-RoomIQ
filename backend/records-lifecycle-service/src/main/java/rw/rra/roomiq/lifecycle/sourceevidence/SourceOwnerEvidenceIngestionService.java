package rw.rra.roomiq.lifecycle.sourceevidence;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

@Service
public class SourceOwnerEvidenceIngestionService {
    private final SourceOwnerEvidenceValidator validator;
    private final SourceOwnerEvidenceRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public SourceOwnerEvidenceIngestionService(SourceOwnerEvidenceValidator validator,
                                              SourceOwnerEvidenceRepository repository,
                                              ObjectMapper objectMapper) {
        this(validator, repository, objectMapper, Clock.systemUTC());
    }

    SourceOwnerEvidenceIngestionService(SourceOwnerEvidenceValidator validator,
                                        SourceOwnerEvidenceRepository repository,
                                        ObjectMapper objectMapper,
                                        Clock clock) {
        this.validator = validator;
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public SourceOwnerEvidenceIngestionResult ingest(String authenticatedSourceService,
                                                     SourceOwnerEvidence incoming) {
        SourceOwnerEvidence evidence = validator.validate(incoming, authenticatedSourceService);
        String digest = digest(evidence);
        repository.lockRecord(evidence.sourceService(), evidence.recordType(), evidence.recordId());

        List<SourceOwnerEvidenceReceipt> sameEventId =
                repository.findBySourceEventId(evidence.sourceService(), evidence.eventId());
        if (!sameEventId.isEmpty()) {
            boolean exactAcceptedReplay = sameEventId.stream().anyMatch(receipt ->
                    receipt.outcome() == SourceOwnerEvidenceOutcome.ACCEPTED
                            && receipt.evidenceSha256().equals(digest)
                            && receipt.evidence().recordType().equals(evidence.recordType())
                            && receipt.evidence().recordId().equals(evidence.recordId()));
            if (exactAcceptedReplay) {
                return result(repository.append(receipt(evidence, authenticatedSourceService, digest,
                        SourceOwnerEvidenceOutcome.DUPLICATE, null)));
            }
            return result(repository.append(receipt(evidence, authenticatedSourceService, digest,
                    SourceOwnerEvidenceOutcome.CONFLICTED, "EVENT_ID_REUSED")));
        }

        SourceOwnerEvidenceReceipt latest = repository.findLatestAccepted(
                evidence.sourceService(), evidence.recordType(), evidence.recordId()).orElse(null);
        if (latest == null && evidence.supersedesEventId() != null) {
            return result(repository.append(receipt(evidence, authenticatedSourceService, digest,
                    SourceOwnerEvidenceOutcome.CONFLICTED, "UNKNOWN_SUPERSEDED_EVENT")));
        }
        if (latest != null && evidence.sourceRevision() < latest.evidence().sourceRevision()) {
            return result(repository.append(receipt(evidence, authenticatedSourceService, digest,
                    SourceOwnerEvidenceOutcome.STALE, "SOURCE_REVISION_OUT_OF_ORDER")));
        }
        if (latest != null && (evidence.sourceRevision() == latest.evidence().sourceRevision()
                || !latest.evidence().eventId().equals(evidence.supersedesEventId()))) {
            return result(repository.append(receipt(evidence, authenticatedSourceService, digest,
                    SourceOwnerEvidenceOutcome.CONFLICTED, "REVISION_SUPERSESSION_CONFLICT")));
        }

        Long conflictRevision = repository.findLatestConflictRevision(
                evidence.sourceService(), evidence.recordType(), evidence.recordId()).orElse(null);
        if (conflictRevision != null && evidence.sourceRevision() <= conflictRevision) {
            return result(repository.append(receipt(evidence, authenticatedSourceService, digest,
                    SourceOwnerEvidenceOutcome.CONFLICTED, "UNRESOLVED_PRIOR_CONFLICT")));
        }
        return result(repository.append(receipt(evidence, authenticatedSourceService, digest,
                SourceOwnerEvidenceOutcome.ACCEPTED, null)));
    }

    private SourceOwnerEvidenceReceipt receipt(SourceOwnerEvidence evidence, String authenticatedSourceService,
                                               String digest, SourceOwnerEvidenceOutcome outcome,
                                               String rejectionCode) {
        return new SourceOwnerEvidenceReceipt(0, evidence, authenticatedSourceService, clock.instant(), digest,
                outcome, rejectionCode);
    }

    private String digest(SourceOwnerEvidence evidence) {
        try {
            byte[] canonicalEvidence = objectMapper.writeValueAsBytes(evidence);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalEvidence));
        } catch (JacksonException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Source evidence could not be fingerprinted", exception);
        }
    }

    private static SourceOwnerEvidenceIngestionResult result(SourceOwnerEvidenceReceipt receipt) {
        return new SourceOwnerEvidenceIngestionResult(receipt.evidence().eventId(),
                receipt.evidence().sourceRevision(), receipt.evidenceSha256(), receipt.receivedAt(),
                receipt.outcome(), receipt.rejectionCode());
    }
}
