package rw.rra.roomiq.notification.domain.service;

import org.springframework.stereotype.Component;
import rw.rra.roomiq.common.web.DomainException;
import rw.rra.roomiq.notification.domain.entity.NotificationTemplate;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class NotificationTemplateRenderer {
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9]*)}}");
    private static final Pattern UNRESOLVED_BRACE = Pattern.compile("[{}]");

    public RenderedTemplate render(NotificationTemplate template, Map<String, Object> values) {
        String subject = template.getSubject() == null ? null
                : renderText(template.getSubject(), values, false);
        String body = renderText(template.getBodyTemplate(), values, true);
        return new RenderedTemplate(subject, body);
    }

    public static void validateSyntax(String templateText) {
        Matcher matcher = VARIABLE.matcher(templateText);
        String withoutVariables = matcher.replaceAll("");
        if (UNRESOLVED_BRACE.matcher(withoutVariables).find()) {
            throw invalidTemplate("Template contains a malformed or unsupported variable");
        }
    }

    private static String renderText(String templateText, Map<String, Object> values, boolean htmlEscape) {
        validateSyntax(templateText);
        Matcher matcher = VARIABLE.matcher(templateText);
        StringBuilder rendered = new StringBuilder(templateText.length());
        int previousEnd = 0;
        while (matcher.find()) {
            rendered.append(templateText, previousEnd, matcher.start());
            String name = matcher.group(1);
            Object value = values.get(name);
            if (value == null) {
                throw invalidTemplate("Template requires a variable that is not available for this event");
            }
            String text = value.toString();
            rendered.append(htmlEscape ? escapeHtml(text) : text.replaceAll("[\\r\\n]+", " "));
            previousEnd = matcher.end();
        }
        rendered.append(templateText, previousEnd, templateText.length());
        return rendered.toString();
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static DomainException invalidTemplate(String message) {
        return new DomainException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                "NOTIFICATION_TEMPLATE_INVALID", message);
    }

    public record RenderedTemplate(String subject, String body) {
    }
}
