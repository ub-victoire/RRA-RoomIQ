package rw.rra.roomiq.notification.integration;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class NotificationEmailProperties {
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+$");

    private final boolean enabled;
    private final String senderAddress;
    private final String senderName;
    private final String smtpHost;
    private final int smtpPort;
    private final String username;
    private final String password;
    private final String tlsMode;
    private final boolean smtpAuth;
    private final boolean startTlsEnabled;
    private final boolean startTlsRequired;
    private final boolean implicitTlsEnabled;
    private final int connectionTimeoutMs;
    private final int readTimeoutMs;
    private final int writeTimeoutMs;

    public NotificationEmailProperties(
            @Value("${roomiq.notification.email.enabled:false}") boolean enabled,
            @Value("${roomiq.notification.email.sender-address:}") String senderAddress,
            @Value("${roomiq.notification.email.sender-name:}") String senderName,
            @Value("${spring.mail.host:}") String smtpHost,
            @Value("${spring.mail.port:0}") int smtpPort,
            @Value("${spring.mail.username:}") String username,
            @Value("${spring.mail.password:}") String password,
            @Value("${roomiq.notification.email.tls-mode:STARTTLS}") String tlsMode,
            @Value("${spring.mail.properties.mail.smtp.auth:false}") boolean smtpAuth,
            @Value("${spring.mail.properties.mail.smtp.starttls.enable:true}") boolean startTlsEnabled,
            @Value("${spring.mail.properties.mail.smtp.starttls.required:true}") boolean startTlsRequired,
            @Value("${spring.mail.properties.mail.smtp.ssl.enable:false}") boolean implicitTlsEnabled,
            @Value("${spring.mail.properties.mail.smtp.connectiontimeout:3000}") int connectionTimeoutMs,
            @Value("${spring.mail.properties.mail.smtp.timeout:5000}") int readTimeoutMs,
            @Value("${spring.mail.properties.mail.smtp.writetimeout:5000}") int writeTimeoutMs) {
        this.enabled = enabled;
        this.senderAddress = senderAddress;
        this.senderName = senderName;
        this.smtpHost = smtpHost;
        this.smtpPort = smtpPort;
        this.username = username;
        this.password = password;
        this.tlsMode = tlsMode;
        this.smtpAuth = smtpAuth;
        this.startTlsEnabled = startTlsEnabled;
        this.startTlsRequired = startTlsRequired;
        this.implicitTlsEnabled = implicitTlsEnabled;
        this.connectionTimeoutMs = connectionTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.writeTimeoutMs = writeTimeoutMs;
    }

    @PostConstruct
    void validateWhenEnabled() {
        if (!enabled) {
            return;
        }
        if (smtpHost.isBlank() || smtpHost.length() > 253 || hasLineBreak(smtpHost)
                || smtpHost.contains(" ") || smtpPort < 1 || smtpPort > 65535
                || !EMAIL.matcher(senderAddress).matches()
                || senderName.isBlank() || senderName.length() > 200 || hasLineBreak(senderName)
                || !("STARTTLS".equals(tlsMode) || "IMPLICIT_TLS".equals(tlsMode))
                || username.isBlank() != password.isBlank()
                || smtpAuth != !username.isBlank()
                || connectionTimeoutMs < 1 || readTimeoutMs < 1 || writeTimeoutMs < 1
                || ("STARTTLS".equals(tlsMode)
                    && (!startTlsEnabled || !startTlsRequired || implicitTlsEnabled))
                || ("IMPLICIT_TLS".equals(tlsMode)
                    && (!implicitTlsEnabled || startTlsEnabled || startTlsRequired))) {
            throw new IllegalStateException(
                    "Enabled notification email requires valid SMTP, TLS, sender, and optional paired authentication settings");
        }
    }

    private static boolean hasLineBreak(String value) {
        return value.contains("\r") || value.contains("\n");
    }

    public boolean isEnabled() { return enabled; }
    public String senderAddress() { return senderAddress; }
    public String senderName() { return senderName; }
}
