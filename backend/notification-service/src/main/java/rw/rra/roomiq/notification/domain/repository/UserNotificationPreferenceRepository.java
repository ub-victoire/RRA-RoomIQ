package rw.rra.roomiq.notification.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rw.rra.roomiq.notification.domain.entity.UserNotificationPreference;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserNotificationPreferenceRepository
        extends JpaRepository<UserNotificationPreference, UUID> {
    List<UserNotificationPreference> findAllByUserIdOrderByEventType(UUID userId);

    Optional<UserNotificationPreference> findByUserIdAndEventType(UUID userId, String eventType);

    List<UserNotificationPreference> findAllByUserIdInAndEventType(
            Collection<UUID> userIds, String eventType);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO user_notification_preference
                (id, user_id, event_type, in_app_enabled, email_enabled)
            VALUES
                (:id, :userId, :eventType, :inAppEnabled, :emailEnabled)
            ON CONFLICT (user_id, event_type)
            DO UPDATE SET
                in_app_enabled = EXCLUDED.in_app_enabled,
                email_enabled = EXCLUDED.email_enabled
            """, nativeQuery = true)
    int upsert(@Param("id") UUID id,
               @Param("userId") UUID userId,
               @Param("eventType") String eventType,
               @Param("inAppEnabled") boolean inAppEnabled,
               @Param("emailEnabled") boolean emailEnabled);
}
