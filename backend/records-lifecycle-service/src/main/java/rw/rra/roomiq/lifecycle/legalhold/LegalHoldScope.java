package rw.rra.roomiq.lifecycle.legalhold;

public record LegalHoldScope(
        LegalHoldScopeType type,
        String sourceService,
        String recordType,
        String recordId,
        String categoryCode) {
}
