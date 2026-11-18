package rw.rra.roomiq.audit.integration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class AuditSourceEvidenceProperties {
    private static final Pattern ACTION = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,127}");
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private final boolean enabled;
    private final String rlcUrl;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final String mappingDiagnostic;
    private final Map<String, Mapping> mappings;

    public AuditSourceEvidenceProperties(
            @Value("${roomiq.audit.source-evidence.enabled:false}") boolean enabled,
            @Value("${roomiq.audit.source-evidence.rlc-url:}") String rlcUrl,
            @Value("${roomiq.audit.source-evidence.connect-timeout:2s}") Duration connectTimeout,
            @Value("${roomiq.audit.source-evidence.read-timeout:5s}") Duration readTimeout,
            @Value("${roomiq.audit.source-evidence.mappings:}") String mappingSpec) {
        this.enabled = enabled;
        this.rlcUrl = rlcUrl;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        ParseResult parsed = parseMappings(mappingSpec);
        this.mappings = parsed.mappings();
        this.mappingDiagnostic = parsed.diagnostic();
        if (enabled) {
            if (mappings.isEmpty() || mappingDiagnostic != null) {
                throw new IllegalStateException("Audit source evidence requires valid explicit event mappings");
            }
            if (!isHttpsUrl(rlcUrl)) {
                throw new IllegalStateException("AUDIT_RLC_SERVICE_URL must be an absolute HTTPS URL");
            }
            if (connectTimeout.isNegative() || connectTimeout.isZero()
                    || readTimeout.isNegative() || readTimeout.isZero()) {
                throw new IllegalStateException("Audit source-evidence timeouts must be positive");
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String rlcUrl() {
        return rlcUrl;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration readTimeout() {
        return readTimeout;
    }

    public String mappingDiagnostic() {
        return mappingDiagnostic;
    }

    public Mapping mappingFor(String action) {
        return mappings.get(action);
    }

    private static ParseResult parseMappings(String specification) {
        Map<String, Mapping> mappings = new HashMap<>();
        if (specification == null || specification.isBlank()) {
            return new ParseResult(Map.of(), null);
        }
        for (String item : specification.split(";", -1)) {
            String[] sides = item.trim().split("=", -1);
            String[] values = sides.length == 2 ? sides[1].split(":", -1) : new String[0];
            if (sides.length != 2 || !ACTION.matcher(sides[0].trim()).matches()
                    || values.length != 2 || !CODE.matcher(values[0].trim()).matches()
                    || !isSupportedStartBasis(values[1].trim())) {
                return new ParseResult(Map.of(), "AUDIT_EVENT_MAPPING_INVALID");
            }
            String action = sides[0].trim();
            if (mappings.putIfAbsent(action,
                    new Mapping(values[0].trim(), values[1].trim())) != null) {
                return new ParseResult(Map.of(), "AUDIT_EVENT_MAPPING_DUPLICATE");
            }
        }
        return new ParseResult(Map.copyOf(mappings), null);
    }

    private static boolean isSupportedStartBasis(String value) {
        return "OCCURRED_AT".equals(value)
                || "ORIGINAL_EVENT".equals(value)
                || "RECORD_CREATED_AT".equals(value);
    }

    private static boolean isHttpsUrl(String value) {
        try {
            URI uri = URI.create(value);
            return uri.isAbsolute() && "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getQuery() == null && uri.getFragment() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public record Mapping(String categoryCode, String startBasisCode) {
    }

    private record ParseResult(Map<String, Mapping> mappings, String diagnostic) {
    }
}
