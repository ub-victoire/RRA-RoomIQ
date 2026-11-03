package rw.rra.roomiq.notification.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record NotificationTemplateCreateRequest(
        @NotBlank @Size(max = 128) @Pattern(regexp = "[A-Za-z][A-Za-z0-9._-]*") String code,
        @Positive int version,
        @NotBlank @Pattern(regexp = "IN_APP|EMAIL") String channel,
        @Size(max = 500) String subject,
        @NotBlank @Size(max = 20000) String bodyTemplate
) {
}
