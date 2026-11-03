package rw.rra.roomiq.identity.domain.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import rw.rra.roomiq.identity.domain.dto.NotificationDeliveryContactRequest;
import rw.rra.roomiq.identity.domain.dto.NotificationDeliveryContactResponse;
import rw.rra.roomiq.identity.domain.entity.UserStatus;
import rw.rra.roomiq.identity.domain.repository.AppUserRepository;

@Service
public class NotificationDeliveryContactService {
    private final AppUserRepository users;

    public NotificationDeliveryContactService(AppUserRepository users) {
        this.users = users;
    }

    @Transactional(readOnly = true)
    public NotificationDeliveryContactResponse resolve(NotificationDeliveryContactRequest request) {
        var user = users.findByIdAndStatus(request.userId(), UserStatus.ACTIVE)
                .orElseThrow(NotificationDeliveryContactService::notFound);
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            throw notFound();
        }
        return new NotificationDeliveryContactResponse(user.getId(), user.getEmail());
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Active notification delivery contact was not found");
    }
}
