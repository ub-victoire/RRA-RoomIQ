package rw.rra.roomiq.audit.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

public interface AuditEventSearchRepository {
    Page<AuditEvent> search(Specification<AuditEvent> specification, Pageable pageable);
}
