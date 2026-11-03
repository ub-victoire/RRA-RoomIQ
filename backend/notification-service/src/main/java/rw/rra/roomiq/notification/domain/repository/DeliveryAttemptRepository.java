package rw.rra.roomiq.notification.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rw.rra.roomiq.notification.domain.entity.DeliveryAttempt;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface DeliveryAttemptRepository extends JpaRepository<DeliveryAttempt, UUID> {
    @Query("""
            select max(attempt.attemptNo) from DeliveryAttempt attempt
            where attempt.notification.id = :notificationId and attempt.attemptedAt >= :cycleStartedAt
            """)
    Short findMaximumAttemptNumberSince(@Param("notificationId") UUID notificationId,
                                        @Param("cycleStartedAt") Instant cycleStartedAt);

    @Query("""
            select attempt from DeliveryAttempt attempt
            where attempt.notification.id = :notificationId
            order by attempt.attemptedAt desc
            """)
    List<DeliveryAttempt> findLatestAttempt(@Param("notificationId") UUID notificationId,
                                             org.springframework.data.domain.Pageable pageable);

    @Query("""
            select attempt from DeliveryAttempt attempt
            where attempt.notification.id in :notificationIds
            order by attempt.attemptedAt asc
            """)
    List<DeliveryAttempt> findAllByNotificationIds(
            @Param("notificationIds") List<UUID> notificationIds);

    @Query("select count(attempt) from DeliveryAttempt attempt where attempt.attemptNo > 1")
    long countRetryAttempts();

    @Query("select count(attempt) from DeliveryAttempt attempt where attempt.result = 'SENT'")
    long countSuccessfulAttempts();

    @Query("select count(attempt) from DeliveryAttempt attempt where attempt.result = 'FAILED'")
    long countFailedAttempts();

    List<DeliveryAttempt> findAllByNotificationIdOrderByAttemptedAtAsc(UUID notificationId);
}
