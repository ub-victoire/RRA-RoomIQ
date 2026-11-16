package rw.rra.roomiq.booking.integration;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

class BookingRlcContractFixtureTests {
    @Test
    void bookingDtosRoundTripTheSharedWireFixture() throws Exception {
        JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
        try (InputStream input = getClass().getResourceAsStream("/contract-fixtures/booking-rlc-v1.json")) {
            assertThat(input).isNotNull();
            JsonNode fixture = mapper.readTree(input);
            assertRoundTrip(mapper, fixture.get("evidence"), BookingSourceEvidence.class);
            assertRoundTrip(mapper, fixture.get("instruction"), BookingHoldInstruction.class);
            assertRoundTrip(mapper, fixture.get("acknowledgement"), BookingHoldAcknowledgement.class);
        }
    }

    private static <T> void assertRoundTrip(JsonMapper mapper, JsonNode expected, Class<T> type) {
        JsonNode actual = mapper.readTree(mapper.writeValueAsString(mapper.treeToValue(expected, type)));
        assertThat(actual).isEqualTo(expected);
    }
}
