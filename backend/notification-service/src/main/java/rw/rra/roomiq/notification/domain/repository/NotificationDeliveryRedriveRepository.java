package rw.rra.roomiq.notification.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import rw.rra.roomiq.notification.domain.entity.NotificationDeliveryRedrive;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationDeliveryRedriveRepository
        extends JpaRepository<NotificationDeliveryRedrive, UUID> {
    Optional<NotificationDeliveryRedrive> findByNotificationIdAndIdempotencyKey(
            UUID notificationId, String idempotencyKey);

    Optional<NotificationDeliveryRedrive> findFirstByNotificationIdOrderByRequestedAtDesc(UUID notificationId);

    List<NotificationDeliveryRedrive> findAllByNotificationIdOrderByRequestedAtAsc(UUID notificationId);
}
