package rw.rra.roomiq.notification.domain.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rw.rra.roomiq.notification.domain.entity.NotificationTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, UUID> {
    List<NotificationTemplate> findAllByOrderByCodeAscChannelAscVersionDesc();

    Optional<NotificationTemplate> findByCodeAndChannelAndActiveTrue(String code, String channel);

    boolean existsByCodeAndVersionAndChannel(String code, int version, String channel);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select template from NotificationTemplate template where template.id = :id")
    Optional<NotificationTemplate> findByIdForUpdate(@Param("id") UUID id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update NotificationTemplate template set template.active = false " +
            "where template.code = :code and template.channel = :channel and template.active = true")
    int deactivateActiveVersions(@Param("code") String code, @Param("channel") String channel);
}
