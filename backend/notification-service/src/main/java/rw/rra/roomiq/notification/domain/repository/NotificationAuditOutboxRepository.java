package rw.rra.roomiq.notification.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rw.rra.roomiq.notification.domain.entity.NotificationAuditOutboxEntry;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface NotificationAuditOutboxRepository
        extends JpaRepository<NotificationAuditOutboxEntry, UUID> {
    @Query("select entry from NotificationAuditOutboxEntry entry " +
            "where entry.publishedAt is null and entry.nextAttemptAt <= :now order by entry.createdAt")
    List<NotificationAuditOutboxEntry> findPending(@Param("now") Instant now,
                                                    org.springframework.data.domain.Pageable pageable);
}
