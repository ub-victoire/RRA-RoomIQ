package rw.rra.roomiq.booking.integration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import rw.rra.roomiq.booking.domain.enums.BookingRequestType;

import java.util.EnumMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "roomiq.booking.source-evidence")
public class BookingSourceEvidenceProperties {
    private boolean enabled;
    private String rlcUrl = "http://localhost:8089";
    private Map<BookingRequestType, Mapping> mappings = new EnumMap<>(BookingRequestType.class);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getRlcUrl() { return rlcUrl; }
    public void setRlcUrl(String rlcUrl) { this.rlcUrl = rlcUrl; }
    public Map<BookingRequestType, Mapping> getMappings() { return mappings; }
    public void setMappings(Map<BookingRequestType, Mapping> mappings) {
        this.mappings = new EnumMap<>(BookingRequestType.class);
        if (mappings != null) {
            this.mappings.putAll(mappings);
        }
    }

    public Mapping mappingFor(BookingRequestType type) {
        return mappings.get(type);
    }

    public static class Mapping {
        private String categoryCode;
        private String startBasisCode;

        public String getCategoryCode() { return categoryCode; }
        public void setCategoryCode(String categoryCode) { this.categoryCode = categoryCode; }
        public String getStartBasisCode() { return startBasisCode; }
        public void setStartBasisCode(String startBasisCode) { this.startBasisCode = startBasisCode; }
    }
}
