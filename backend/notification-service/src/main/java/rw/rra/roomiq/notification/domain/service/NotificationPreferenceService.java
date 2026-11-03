package rw.rra.roomiq.notification.domain.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.rra.roomiq.common.web.DomainException;
import rw.rra.roomiq.notification.domain.dto.NotificationPreferenceResponse;
import rw.rra.roomiq.notification.domain.dto.NotificationPreferenceUpdateRequest;
import rw.rra.roomiq.notification.domain.entity.UserNotificationPreference;
import rw.rra.roomiq.notification.domain.event.NotificationEventCodec;
import rw.rra.roomiq.notification.domain.repository.UserNotificationPreferenceRepository;
import rw.rra.roomiq.notification.integration.NotificationIdentityAuthenticationClient;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class NotificationPreferenceService {
    private static final String IN_APP = "IN_APP";
    private static final String EMAIL = "EMAIL";

    private final UserNotificationPreferenceRepository preferenceRepository;
    private final NotificationIdentityAuthenticationClient identityAuthenticationClient;
    private final NotificationEventCodec eventCodec;

    public NotificationPreferenceService(
            UserNotificationPreferenceRepository preferenceRepository,
            NotificationIdentityAuthenticationClient identityAuthenticationClient,
            NotificationEventCodec eventCodec) {
        this.preferenceRepository = preferenceRepository;
        this.identityAuthenticationClient = identityAuthenticationClient;
        this.eventCodec = eventCodec;
    }

    @Transactional(readOnly = true)
    public List<NotificationPreferenceResponse> getPreferences(String authorization) {
        UUID userId = identityAuthenticationClient.authenticate(authorization);
        Map<String, UserNotificationPreference> saved = preferenceRepository
                .findAllByUserIdOrderByEventType(userId).stream()
                .collect(Collectors.toMap(UserNotificationPreference::getEventType, Function.identity()));
        return eventCodec.supportedEventTypes().stream()
                .sorted(Comparator.naturalOrder())
                .map(eventType -> toResponse(eventType, saved.get(eventType)))
                .toList();
    }

    @Transactional
    public NotificationPreferenceResponse updatePreference(
            String authorization, String eventType, NotificationPreferenceUpdateRequest request) {
        UUID userId = identityAuthenticationClient.authenticate(authorization);
        validateEventType(eventType);
        if (request == null || request.inAppEnabled() == null || request.emailEnabled() == null) {
            throw invalidPreference("Both inAppEnabled and emailEnabled are required");
        }

        preferenceRepository.upsert(UUID.randomUUID(), userId, eventType,
                request.inAppEnabled(), request.emailEnabled());
        UserNotificationPreference preference = preferenceRepository.findByUserIdAndEventType(userId, eventType)
                .orElseThrow(() -> new IllegalStateException("Updated notification preference was not persisted"));
        return toResponse(eventType, preference);
    }

    @Transactional(readOnly = true)
    public List<UUID> filterRecipients(String eventType, Collection<UUID> recipientIds, String channel) {
        validateEventType(eventType);
        validateChannel(channel);
        if (recipientIds.isEmpty()) {
            return List.of();
        }

        List<UUID> distinctRecipients = recipientIds.stream().distinct().toList();
        Map<UUID, UserNotificationPreference> saved = preferenceRepository
                .findAllByUserIdInAndEventType(distinctRecipients, eventType).stream()
                .collect(Collectors.toMap(UserNotificationPreference::getUserId, Function.identity()));
        return distinctRecipients.stream()
                .filter(recipient -> isEnabled(saved.get(recipient), channel))
                .toList();
    }

    @Transactional(readOnly = true)
    public boolean isChannelEnabled(UUID userId, String eventType, String channel) {
        validateEventType(eventType);
        validateChannel(channel);
        return preferenceRepository.findByUserIdAndEventType(userId, eventType)
                .map(preference -> isEnabled(preference, channel))
                .orElse(EMAIL.equals(channel) ? false : true);
    }

    private NotificationPreferenceResponse toResponse(String eventType, UserNotificationPreference preference) {
        return new NotificationPreferenceResponse(
                eventType,
                preference == null || preference.isInAppEnabled(),
                preference != null && preference.isEmailEnabled(),
                preference != null);
    }

    private void validateEventType(String eventType) {
        if (!eventCodec.supportedEventTypes().contains(eventType)) {
            throw invalidPreference("eventType is not a supported notification event");
        }
    }

    private static boolean isEnabled(UserNotificationPreference preference, String channel) {
        if (preference == null) {
            return IN_APP.equals(channel);
        }
        return IN_APP.equals(channel) ? preference.isInAppEnabled() : preference.isEmailEnabled();
    }

    private static void validateChannel(String channel) {
        if (!IN_APP.equals(channel) && !EMAIL.equals(channel)) {
            throw invalidPreference("channel must be IN_APP or EMAIL");
        }
    }

    private static DomainException invalidPreference(String message) {
        return new DomainException(HttpStatus.BAD_REQUEST, "INVALID_NOTIFICATION_PREFERENCE", message);
    }
}
