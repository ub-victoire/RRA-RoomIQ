package rw.rra.roomiq.identity.domain.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import rw.rra.roomiq.identity.domain.dto.NotificationDeliveryContactRequest;
import rw.rra.roomiq.identity.domain.entity.AppUser;
import rw.rra.roomiq.identity.domain.entity.UserStatus;
import rw.rra.roomiq.identity.domain.repository.AppUserRepository;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class NotificationDeliveryContactServiceTests {
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final NotificationDeliveryContactService service = new NotificationDeliveryContactService(users);

    @Test
    void resolvesOnlyTheStoredAddressForAnActiveAccount() {
        UUID userId = UUID.randomUUID();
        AppUser user = new AppUser("trusted@example.org", "Trusted User", UserStatus.ACTIVE);
        ReflectionTestUtils.setField(user, "id", userId);
        when(users.findByIdAndStatus(userId, UserStatus.ACTIVE)).thenReturn(Optional.of(user));

        assertThat(service.resolve(new NotificationDeliveryContactRequest(userId)))
                .satisfies(contact -> {
                    assertThat(contact.userId()).isEqualTo(userId);
                    assertThat(contact.email()).isEqualTo("trusted@example.org");
                });
        verify(users).findByIdAndStatus(userId, UserStatus.ACTIVE);
    }

    @Test
    void doesNotResolveMissingInactiveOrAddresslessAccounts() {
        UUID userId = UUID.randomUUID();
        when(users.findByIdAndStatus(userId, UserStatus.ACTIVE)).thenReturn(Optional.empty());

        assertNotFound(userId);
        verify(users).findByIdAndStatus(userId, UserStatus.ACTIVE);

        AppUser addresslessUser = new AppUser("", "No Address", UserStatus.ACTIVE);
        ReflectionTestUtils.setField(addresslessUser, "id", userId);
        when(users.findByIdAndStatus(userId, UserStatus.ACTIVE)).thenReturn(Optional.of(addresslessUser));
        assertNotFound(userId);
    }

    private void assertNotFound(UUID userId) {
        assertThatThrownBy(() -> service.resolve(new NotificationDeliveryContactRequest(userId)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }
}
