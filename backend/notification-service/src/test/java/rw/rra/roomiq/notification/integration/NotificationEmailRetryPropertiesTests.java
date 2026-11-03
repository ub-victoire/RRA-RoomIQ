package rw.rra.roomiq.notification.integration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationEmailRetryPropertiesTests {
    @Test
    void appliesApprovedExponentialBackoffAndAttemptLimit() {
        NotificationEmailRetryProperties properties =
                new NotificationEmailRetryProperties(5, 60_000, 2, 480_000, "");

        assertThat(properties.delayAfterAttemptMs(1)).isEqualTo(60_000);
        assertThat(properties.delayAfterAttemptMs(2)).isEqualTo(120_000);
        assertThat(properties.delayAfterAttemptMs(3)).isEqualTo(240_000);
        assertThat(properties.delayAfterAttemptMs(4)).isEqualTo(480_000);
        assertThat(properties.delayAfterAttemptMs(5)).isEqualTo(480_000);
        assertThat(properties.hasRetryRemaining(4)).isTrue();
        assertThat(properties.hasRetryRemaining(5)).isFalse();
    }

    @Test
    void onlyRetriesTemporarySmtpCodesUnlessProviderSpecificFiveHundredCodeIsConfigured() {
        NotificationEmailRetryProperties defaultPolicy =
                new NotificationEmailRetryProperties(5, 60_000, 2, 480_000, "");
        NotificationEmailRetryProperties providerPolicy =
                new NotificationEmailRetryProperties(5, 60_000, 2, 480_000, "550, 554");

        assertThat(defaultPolicy.isRetryableSmtpResponseCode(450)).isTrue();
        assertThat(defaultPolicy.isRetryableSmtpResponseCode(550)).isFalse();
        assertThat(providerPolicy.isRetryableSmtpResponseCode(554)).isTrue();
    }

    @Test
    void rejectsUnboundedOrMalformedRetryConfiguration() {
        assertThatThrownBy(() -> new NotificationEmailRetryProperties(0, 60_000, 2, 480_000, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotificationEmailRetryProperties(5, 60_000, 2, 30_000, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotificationEmailRetryProperties(5, 60_000, 2, 480_000, "450"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
