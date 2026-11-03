package rw.rra.roomiq.notification.domain.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import rw.rra.roomiq.notification.domain.entity.Notification;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;

public interface NotificationRepository extends JpaRepository<Notification, UUID>, JpaSpecificationExecutor<Notification> {
    @Modifying
    @Query(value = """
            INSERT INTO notification
                (id, recipient_user_id, notification_template_id, event_type, resource_type, resource_id, channel, status,
                 payload, scheduled_at, cancelled_at, created_at)
            VALUES
                (:id, :recipientUserId, :templateId, 'Reminder', :resourceType, :resourceId, :channel, 'SCHEDULED',
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
                                        @Param("templateId") UUID templateId,
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

    Optional<Notification> findByIdAndRecipientUserIdAndChannel(
            UUID notificationId, UUID recipientUserId, String channel);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE notification
            SET read_at = COALESCE(read_at, :readAt)
            WHERE id = :notificationId AND recipient_user_id = :recipientUserId AND channel = 'IN_APP'
            """, nativeQuery = true)
    int markReadIfOwned(@Param("notificationId") UUID notificationId,
                        @Param("recipientUserId") UUID recipientUserId,
                        @Param("readAt") Instant readAt);

    Page<Notification> findByRecipientUserIdOrderByCreatedAtDesc(UUID recipientUserId, Pageable pageable);

    @Query(value = """
            SELECT *
            FROM notification
            WHERE channel = 'EMAIL'
              AND (status = 'PENDING' OR (status = 'SCHEDULED' AND scheduled_at <= :now))
            ORDER BY COALESCE(scheduled_at, created_at), created_at, id
            FOR UPDATE SKIP LOCKED
            LIMIT 1
            """, nativeQuery = true)
    Optional<Notification> lockNextDueEmail(@Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select notification from Notification notification where notification.id = :id and notification.channel = 'EMAIL'")
    Optional<Notification> lockEmailById(@Param("id") UUID id);

    Page<Notification> findByChannelAndStatusOrderByCreatedAtAsc(
            String channel, String status, Pageable pageable);

    Optional<Notification> findByIdAndChannelAndStatus(UUID id, String channel, String status);

    @Query(value = """
            SELECT COUNT(*) FROM notification
            WHERE channel = 'EMAIL' AND status IN ('PENDING', 'SCHEDULED')
            """, nativeQuery = true)
    long countQueuedEmails();

    @Query(value = """
            SELECT MIN(created_at) FROM notification
            WHERE channel = 'EMAIL' AND status IN ('PENDING', 'SCHEDULED')
            """, nativeQuery = true)
    Instant findOldestQueuedEmailAt();

    @Query(value = """
            SELECT COUNT(*) FROM notification
            WHERE channel = 'EMAIL' AND status = 'DEAD_LETTER'
            """, nativeQuery = true)
    long countDeadLetteredEmails();
}
