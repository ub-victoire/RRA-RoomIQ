package rw.rra.roomiq.notification.domain.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rw.rra.roomiq.notification.domain.entity.Notification;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID>, JpaSpecificationExecutor<Notification> {
    @Modifying
    @Query(value = """
            INSERT INTO notification
                (id, recipient_user_id, event_type, resource_type, resource_id, channel, status,
                 payload, scheduled_at, cancelled_at, created_at)
            VALUES
                (:id, :recipientUserId, 'Reminder', :resourceType, :resourceId, :channel, 'SCHEDULED',
                 CAST(:payload AS jsonb), :scheduledAt, NULL, :createdAt)
            ON CONFLICT (recipient_user_id, channel, resource_type, resource_id, scheduled_at)
                WHERE event_type = 'Reminder'
            DO NOTHING
            """, nativeQuery = true)
    int insertScheduledReminderIfAbsent(@Param("id") UUID id,
                                        @Param("recipientUserId") UUID recipientUserId,
                                        @Param("resourceType") String resourceType,
                                        @Param("resourceId") UUID resourceId,
                                        @Param("channel") String channel,
                                        @Param("payload") String payload,
                                        @Param("scheduledAt") Instant scheduledAt,
                                        @Param("createdAt") Instant createdAt);

    @Modifying
    @Query(value = """
            UPDATE notification
            SET status = 'CANCELLED', scheduled_at = NULL, cancelled_at = :cancelledAt
            WHERE event_type = 'Reminder' AND resource_type = :resourceType
              AND resource_id = :resourceId AND status IN ('SCHEDULED', 'PENDING')
              AND read_at IS NULL
            """, nativeQuery = true)
    int cancelScheduledReminders(@Param("resourceType") String resourceType,
                                 @Param("resourceId") UUID resourceId,
                                 @Param("cancelledAt") Instant cancelledAt);

    @Query(value = """
            SELECT DISTINCT resource_id
            FROM notification
            WHERE event_type = 'Reminder' AND resource_type = :resourceType
              AND status = 'SCHEDULED' AND scheduled_at <= :now
            ORDER BY resource_id
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findDueReminderResourceIds(@Param("resourceType") String resourceType,
                                          @Param("now") Instant now,
                                          @Param("limit") int limit);

    @Query(value = """
            SELECT *
            FROM notification
            WHERE event_type = 'Reminder' AND resource_type = :resourceType
              AND resource_id = :resourceId AND status = 'SCHEDULED' AND scheduled_at <= :now
            ORDER BY scheduled_at, id
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Notification> findDueRemindersForUpdate(@Param("resourceType") String resourceType,
                                                  @Param("resourceId") UUID resourceId,
                                                  @Param("now") Instant now);

    Optional<Notification> findByIdAndRecipientUserId(UUID notificationId, UUID recipientUserId);

    Page<Notification> findByRecipientUserIdOrderByCreatedAtDesc(UUID recipientUserId, Pageable pageable);

    List<Notification> findAllByRecipientUserIdOrderByCreatedAtDesc(UUID recipientUserId);
}
