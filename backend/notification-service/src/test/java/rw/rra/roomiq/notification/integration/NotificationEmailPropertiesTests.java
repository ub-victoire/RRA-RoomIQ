package rw.rra.roomiq.notification.integration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationEmailPropertiesTests {
    @Test
    void disabledEmailDoesNotRequireAProviderOrSenderConfiguration() {
        NotificationEmailProperties properties = new NotificationEmailProperties(
                false, "", "", "", 0, "", "", "STARTTLS",
                false, true, true, false, 3000, 5000, 5000);

        assertThatCode(properties::validateWhenEnabled).doesNotThrowAnyException();
    }

    @Test
    void enabledEmailRequiresAConsistentTlsAndAuthenticationConfiguration() {
        NotificationEmailProperties invalidTls = new NotificationEmailProperties(
                true, "notifications@example.org", "RRA RoomIQ", "smtp.example.org", 587,
                "", "", "STARTTLS", false, false, false, false, 3000, 5000, 5000);
        NotificationEmailProperties missingAuthentication = new NotificationEmailProperties(
                true, "notifications@example.org", "RRA RoomIQ", "smtp.example.org", 587,
                "roomiq", "", "STARTTLS", false, true, true, false, 3000, 5000, 5000);
        NotificationEmailProperties valid = new NotificationEmailProperties(
                true, "notifications@example.org", "RRA RoomIQ", "smtp.example.org", 587,
                "roomiq", "secret", "STARTTLS", true, true, true, false,
                3000, 5000, 5000);
        NotificationEmailProperties infiniteTimeout = new NotificationEmailProperties(
                true, "notifications@example.org", "RRA RoomIQ", "smtp.example.org", 587,
                "", "", "STARTTLS", false, true, true, false,
                0, 5000, 5000);

        assertThatThrownBy(invalidTls::validateWhenEnabled).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(missingAuthentication::validateWhenEnabled).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(infiniteTimeout::validateWhenEnabled).isInstanceOf(IllegalStateException.class);
        assertThatCode(valid::validateWhenEnabled).doesNotThrowAnyException();
    }
}
