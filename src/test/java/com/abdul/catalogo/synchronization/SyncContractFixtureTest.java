package com.abdul.catalogo.synchronization;

import com.abdul.catalogo.synchronization.dto.DeviceRegistrationRequest;
import com.abdul.catalogo.synchronization.dto.DeviceRegistrationResponse;
import com.abdul.catalogo.synchronization.dto.DiscoveryResponse;
import com.abdul.catalogo.synchronization.dto.SyncBootstrapResponse;
import com.abdul.catalogo.synchronization.dto.SyncPullAckRequest;
import com.abdul.catalogo.synchronization.dto.SyncPullAckResponse;
import com.abdul.catalogo.synchronization.dto.SyncPullResponse;
import com.abdul.catalogo.synchronization.dto.SyncPushRequest;
import com.abdul.catalogo.synchronization.dto.SyncPushResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest
class SyncContractFixtureTest {
    private static final Path FIXTURES = Path.of("docs", "contracts", "examples");

    @Autowired ObjectMapper objectMapper;

    @Test
    void everyOfficialFixtureDeserializesWithTheBackendDtos() throws Exception {
        assertRoundTrip("device-registration-request.json", DeviceRegistrationRequest.class);
        assertRoundTrip("device-registration-response.json", DeviceRegistrationResponse.class);
        assertRoundTrip("push-request.json", SyncPushRequest.class);
        assertRoundTrip("push-response.json", SyncPushResponse.class);
        assertRoundTrip("pull-response.json", SyncPullResponse.class);
        assertRoundTrip("pull-ack-request.json", SyncPullAckRequest.class);
        assertRoundTrip("pull-ack-response.json", SyncPullAckResponse.class);
        assertRoundTrip("bootstrap-response.json", SyncBootstrapResponse.class);
        assertRoundTrip("discovery-response.json", DiscoveryResponse.class);
        assertThat(read("product-aggregate.json").propertyNames()).containsExactlyInAnyOrderElementsOf(Set.of(
                "productId", "code", "name", "description", "company", "companyId", "brand", "brandId",
                "category", "categoryId", "subcategory", "subcategoryId", "productType", "status",
                "attributes", "variants", "presentations", "prices", "images"));
    }

    @Test
    void pushAndPullUseOnlyVersionAndSequenceNames() throws Exception {
        JsonNode result = read("push-response.json").path("results").get(0);
        assertThat(result.propertyNames()).containsExactlyInAnyOrder(
                "eventId", "status", "version", "sequence", "conflictId", "message");
        JsonNode change = read("pull-response.json").path("changes").get(0);
        assertThat(change.propertyNames()).containsExactlyInAnyOrder(
                "sequence", "entityType", "entityId", "operation", "version", "originDeviceId",
                "conflictId", "payload", "changedAt");

        String allFixtures = Files.readString(FIXTURES.resolve("push-response.json"))
                + Files.readString(FIXTURES.resolve("pull-response.json"));
        assertThat(allFixtures).doesNotContain("serverVersion", "serverSequence");
    }

    @Test
    void frozenVersionsArePresentInTheOfficialRequests() throws Exception {
        JsonNode registration = read("device-registration-request.json");
        JsonNode push = read("push-request.json");
        JsonNode event = push.path("events").get(0);
        assertThat(registration.path("apiContractVersion").asText()).isEqualTo("1.0");
        assertThat(push.path("apiContractVersion").asText()).isEqualTo("1.0");
        assertThat(event.path("payloadVersion").asInt()).isEqualTo(1);
        assertThat(event.path("schemaVersion").asText()).isEqualTo("1.0");
    }

    private <T> void assertRoundTrip(String fileName, Class<T> type) throws Exception {
        JsonNode fixture = read(fileName);
        T value = objectMapper.readValue(fixture.toString(), type);
        JsonNode serialized = objectMapper.valueToTree(value);
        assertThat(serialized.propertyNames()).containsExactlyInAnyOrderElementsOf(fixture.propertyNames());
    }

    private JsonNode read(String fileName) throws Exception {
        return objectMapper.readTree(Files.readString(FIXTURES.resolve(fileName)));
    }
}
