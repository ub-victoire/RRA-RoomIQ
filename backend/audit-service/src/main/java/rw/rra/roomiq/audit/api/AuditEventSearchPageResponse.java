package rw.rra.roomiq.audit.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "AuditEventSearchPage", description = "Paged Audit event search results.")
public record AuditEventSearchPageResponse(
        List<AuditEventSearchItemResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        String sortBy,
        String sortDirection) {
}
