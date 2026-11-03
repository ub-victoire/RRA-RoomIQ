package rw.rra.roomiq.notification.domain.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import rw.rra.roomiq.common.web.DomainException;
import rw.rra.roomiq.notification.domain.entity.Notification;
import rw.rra.roomiq.notification.domain.repository.NotificationRepository;
import rw.rra.roomiq.notification.integration.NotificationIdentityAuthenticationClient;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
@AutoConfigureMockMvc
class NotificationInboxServiceTests {
    private static final String AUTHORIZATION = "Bearer inbox-test-token";

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationInboxService notificationInboxService;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationIdentityAuthenticationClient identityAuthenticationClient;

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
        when(identityAuthenticationClient.authenticate(AUTHORIZATION)).thenReturn(owner);

        var page = notificationInboxService.list(
                AUTHORIZATION,
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
        verify(identityAuthenticationClient).authenticate(AUTHORIZATION);
    }

    @Test
    void filtersAndPaginatesOnlyTheAuthenticatedUsersNotifications() {
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        Instant firstCreated = Instant.parse("2026-10-01T10:00:00Z");
        Instant secondCreated = firstCreated.plus(Duration.ofMinutes(1));
        Notification first = notificationRepository.save(new Notification(UUID.randomUUID(), owner,
                "BookingApproved", "BOOKING_REQUEST", UUID.randomUUID(),
                "{\"decisionComment\":\"Approved\",\"message\":\"Approved safely\",\"templateCode\":\"BookingApproved\",\"templateVersion\":2,\"requesterUserId\":\"private-user-id\",\"email\":\"private@example.invalid\"}",
                firstCreated, "PENDING"));
        Notification second = notificationRepository.save(new Notification(UUID.randomUUID(), owner,
                "BookingApproved", "BOOKING_REQUEST", UUID.randomUUID(),
                "{\"decisionComment\":\"Approved\"}", secondCreated, "PENDING"));
        notificationRepository.save(new Notification(UUID.randomUUID(), owner, "BookingRejected",
                "BOOKING_REQUEST", UUID.randomUUID(), "{\"decisionComment\":\"Rejected\"}", secondCreated, "PENDING"));
        notificationRepository.save(new Notification(UUID.randomUUID(), other, "BookingApproved",
                "BOOKING_REQUEST", UUID.randomUUID(), "{\"decisionComment\":\"Other user\"}", firstCreated, "PENDING"));
        when(identityAuthenticationClient.authenticate(AUTHORIZATION)).thenReturn(owner);
        notificationInboxService.markRead(AUTHORIZATION, first.getId());
        notificationInboxService.markRead(AUTHORIZATION, second.getId());

        var page = notificationInboxService.list(AUTHORIZATION, "BookingApproved", "pending", true,
                0, 1, "createdAt", "asc");

        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(1);
        assertThat(page.hasNext()).isTrue();
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().id()).isEqualTo(first.getId());
        assertThat(page.items().getFirst().payload())
                .containsEntry("decisionComment", "Approved")
                .containsEntry("message", "Approved safely")
                .containsEntry("templateVersion", 2)
                .doesNotContainKey("requesterUserId")
                .doesNotContainKey("email");
    }

    @Test
    void rejectsUnsupportedFiltersAndUnsafeSortProperties() {
        UUID owner = UUID.randomUUID();
        when(identityAuthenticationClient.authenticate(AUTHORIZATION)).thenReturn(owner);

        assertThatThrownBy(() -> notificationInboxService.list(AUTHORIZATION, null, "UNKNOWN",
                null, 0, 20, "createdAt", "desc"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("status");
        assertThatThrownBy(() -> notificationInboxService.list(AUTHORIZATION, null, null,
                null, 0, 20, "payload", "desc"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("sortBy");
        assertThatThrownBy(() -> notificationInboxService.list(AUTHORIZATION, null, null,
                null, -1, 20, "createdAt", "desc"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("Page");
        assertThatThrownBy(() -> notificationInboxService.list(AUTHORIZATION, null, null,
                null, 0, 101, "createdAt", "desc"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("size");
    }

    @Test
    void markReadRejectsNotificationsBelongingToAnotherUserAndIsIdempotentForOwner() {
        UUID owner = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        Notification notification = notificationRepository.save(new Notification(UUID.randomUUID(), owner,
                "BookingRequested", "BOOKING_REQUEST", UUID.randomUUID(),
                "{\"eventType\":\"BookingRequested\"}", Instant.now(), "PENDING"));
        when(identityAuthenticationClient.authenticate(AUTHORIZATION)).thenReturn(otherUser, owner, owner);

        assertThatThrownBy(() -> notificationInboxService.markRead(AUTHORIZATION, notification.getId()))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("status", org.springframework.http.HttpStatus.NOT_FOUND);

        var updated = notificationInboxService.markRead(AUTHORIZATION, notification.getId());
        var replay = notificationInboxService.markRead(AUTHORIZATION, notification.getId());

        assertThat(updated.readAt()).isNotNull();
        assertThat(replay.readAt()).isEqualTo(updated.readAt());
        assertThat(notificationRepository.findById(notification.getId()).orElseThrow().getReadAt()).isNotNull();
    }

    @Test
    void controllerUsesSharedSuccessAndErrorEnvelopesAndRequiresIdentityBearer() throws Exception {
        UUID owner = UUID.randomUUID();
        notificationRepository.save(new Notification(UUID.randomUUID(), owner, "BookingRequested",
                "BOOKING_REQUEST", UUID.randomUUID(), "{\"eventType\":\"BookingRequested\"}",
                Instant.now(), "PENDING"));
        when(identityAuthenticationClient.authenticate(AUTHORIZATION)).thenReturn(owner);
        when(identityAuthenticationClient.authenticate(isNull())).thenThrow(
                new DomainException(org.springframework.http.HttpStatus.UNAUTHORIZED,
                        "AUTHENTICATION_REQUIRED", "An active Identity account is required"));

        mockMvc.perform(get("/api/v1/notifications")
                        .header("Authorization", AUTHORIZATION)
                        .queryParam("page", "0")
                        .queryParam("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalElements").value(1));

        mockMvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }
}
