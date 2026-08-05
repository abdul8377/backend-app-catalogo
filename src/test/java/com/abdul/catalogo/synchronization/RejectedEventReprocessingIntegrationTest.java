package com.abdul.catalogo.synchronization;

import com.abdul.catalogo.synchronization.repository.ProcessedEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class RejectedEventReprocessingIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired ProcessedEventRepository processedEventRepository;

    private String deviceId;
    private String token;

    @BeforeEach
    void registerDevice() throws Exception {
        String pairing = mockMvc.perform(post("/admin/pairing-codes")
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String code = objectMapper.readTree(pairing).path("pairingCode").asText();
        String response = mockMvc.perform(post("/api/v1/devices/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Tablet reproceso",
                                "platform", "android",
                                "pairingCode", code,
                                "appVersion", "1.0.0",
                                "apiContractVersion", "1.0"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode registration = objectMapper.readTree(response);
        deviceId = registration.path("deviceId").asText();
        token = registration.path("token").asText();
    }

    @Test
    void rejectedEventIsValidatedAgainInsteadOfBeingReportedAsAlreadyProcessed() throws Exception {
        String eventId = UUID.randomUUID().toString();
        long before = processedEventRepository.count();
        String request = objectMapper.writeValueAsString(Map.of(
                "deviceId", deviceId,
                "apiContractVersion", "1.0",
                "events", List.of(Map.ofEntries(
                        Map.entry("eventId", eventId),
                        Map.entry("entityType", "UNKNOWN_ENTITY"),
                        Map.entry("entityId", UUID.randomUUID().toString()),
                        Map.entry("operation", "UPSERT"),
                        Map.entry("baseVersion", 0),
                        Map.entry("payloadVersion", 1),
                        Map.entry("schemaVersion", "1.0"),
                        Map.entry("occurredAt", Instant.now().toString()),
                        Map.entry("payload", Map.of("name", "dato"))
                ))));

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/api/v1/sync/push")
                            .header("X-Device-Id", deviceId)
                            .header("X-Device-Token", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(request))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.results[0].status").value("REJECTED"));
        }

        assertThat(processedEventRepository.count()).isEqualTo(before + 1);
        assertThat(processedEventRepository.findById(eventId)).isPresent();
    }
}
