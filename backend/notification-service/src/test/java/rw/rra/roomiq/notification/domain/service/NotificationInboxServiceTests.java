package rw.rra.roomiq.notification.domain.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;
import rw.rra.roomiq.notification.domain.entity.Notification;
import rw.rra.roomiq.notification.domain.repository.NotificationRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:notification-inbox;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driverClassName=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "roomiq.notification.events.enabled=false",
        "roomiq.notification.reminders.delivery-enabled=false"
})
class NotificationInboxServiceTests {
    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationInboxService notificationInboxService;

    @BeforeEach
    void reset() {
        notificationRepository.deleteAll();
    }

    @Test
    void listInboxShowsOnlyAuthenticatedUsersNotifications() {
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        notificationRepository.save(new Notification(UUID.randomUUID(), owner, "BookingRequested",
                "BOOKING_REQUEST", UUID.randomUUID(), "{\"eventType\":\"BookingRequested\"}", Instant.now(), "PENDING"));
        notificationRepository.save(new Notification(UUID.randomUUID(), owner, "BookingApproved",
                "BOOKING_REQUEST", UUID.randomUUID(), "{\"decision\":\"approved\"}", Instant.now(), "PENDING"));
        notificationRepository.save(new Notification(UUID.randomUUID(), other, "BookingCancelled",
                "CANCELLATION", UUID.randomUUID(), "{\"eventType\":\"BookingCancelled\"}", Instant.now(), "PENDING"));

        var page = notificationInboxService.list(
                new UsernamePasswordAuthenticationToken(owner.toString(), "secret", List.of()),
                null,
                "PENDING",
                false,
                0,
                20,
                "createdAt",
                "desc"
        );

        assertThat(page.items()).hasSize(2);
        assertThat(page.items()).allSatisfy(item -> assertThat(item.recipientUserId()).isEqualTo(owner));
    }

    @Test
    void markReadRejectsNotificationsBelongingToAnotherUser() {
        UUID owner = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        Notification notification = notificationRepository.save(new Notification(UUID.randomUUID(), owner,
                "BookingRequested", "BOOKING_REQUEST", UUID.randomUUID(),
                "{\"eventType\":\"BookingRequested\"}", Instant.now(), "PENDING"));

        assertThatThrownBy(() -> notificationInboxService.markRead(
                new UsernamePasswordAuthenticationToken(otherUser.toString(), "secret", List.of()),
                notification.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not found");

        var updated = notificationInboxService.markRead(
                new UsernamePasswordAuthenticationToken(owner.toString(), "secret", List.of()),
                notification.getId());

        assertThat(updated.readAt()).isNotNull();
        assertThat(notificationRepository.findById(notification.getId()).orElseThrow().getReadAt()).isNotNull();
    }
}
