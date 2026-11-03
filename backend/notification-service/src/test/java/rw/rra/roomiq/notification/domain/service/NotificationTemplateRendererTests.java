package rw.rra.roomiq.notification.domain.service;

import org.junit.jupiter.api.Test;
import rw.rra.roomiq.notification.domain.entity.NotificationTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationTemplateRendererTests {
    private final NotificationTemplateRenderer renderer = new NotificationTemplateRenderer();

    @Test
    void rendersDeclaredValuesWithHtmlEscapingAndSafeSubjectSubstitution() {
        NotificationTemplate template = new NotificationTemplate(UUID.randomUUID(), "BookingRejected",
                2, "EMAIL", "Decision for {{eventType}}: {{decisionComment}}",
                "<p>{{decisionComment}}</p>");

        var rendered = renderer.render(template, Map.of(
                "eventType", "BookingRejected",
                "decisionComment", "<script>alert('x')</script>\r\nInjected"));

        assertThat(rendered.subject()).isEqualTo("Decision for BookingRejected: <script>alert('x')</script> Injected");
        assertThat(rendered.body()).isEqualTo(
                "<p>&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;\r\nInjected</p>");
    }

    @Test
    void failsForMissingVariablesAndMalformedExpressions() {
        NotificationTemplate template = new NotificationTemplate(UUID.randomUUID(), "BookingApproved",
                1, "IN_APP", null, "Approved: {{decisionComment}}");

        assertThatThrownBy(() -> renderer.render(template, Map.of("eventType", "BookingApproved")))
                .hasMessageContaining("variable that is not available");
        assertThatThrownBy(() -> NotificationTemplateRenderer.validateSyntax("Malformed {{decisionComment"))
                .hasMessageContaining("malformed");
    }
}
