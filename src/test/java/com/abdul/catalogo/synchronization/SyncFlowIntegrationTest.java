package com.abdul.catalogo.synchronization;

import com.abdul.catalogo.product.repository.ProductRepository;
import com.abdul.catalogo.synchronization.repository.ChangeLogRepository;
import com.abdul.catalogo.synchronization.repository.ProcessedEventRepository;
import com.abdul.catalogo.synchronization.repository.SyncConflictRepository;
import com.abdul.catalogo.synchronization.repository.SyncRecordRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class SyncFlowIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired SyncConflictRepository conflictRepository;
    @Autowired ProcessedEventRepository eventRepository;
    @Autowired ChangeLogRepository changeRepository;
    @Autowired SyncRecordRepository recordRepository;
    @Autowired ProductRepository productRepository;

    private String deviceId;
    private String token;

    @BeforeEach
    void registerDevice() throws Exception {
        String response = mockMvc.perform(post("/api/v1/devices/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Tablet de pruebas\",\"platform\":\"android\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode registration = objectMapper.readTree(response);
        deviceId = registration.get("deviceId").asText();
        token = registration.get("token").asText();
    }

    @Test
    void synchronizesAllEntityTypesIdempotentlyAndPublishesWebProducts() throws Exception {
        String companyId = UUID.randomUUID().toString();
        String productId = UUID.randomUUID().toString();
        String companyEventId = UUID.randomUUID().toString();
        String productEventId = UUID.randomUUID().toString();

        Map<String, Object> companyEvent = event(companyEventId, "COMPANY", companyId, 0,
                Map.of("syncId", companyId, "name", "Empresa Demo"));
        Map<String, Object> productEvent = event(productEventId, "PRODUCT", productId, 0,
                Map.of("productId", productId, "code", "TAB-001", "name", "Taladro de prueba",
                        "company", "Empresa Demo", "brand", "Marca Demo", "category", "Taladros",
                        "status", "ACTIVE", "variants", List.of()));

        mockMvc.perform(authenticated(post("/api/v1/sync/push"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "deviceId", deviceId,
                                "events", List.of(companyEvent, productEvent)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$.results[1].status").value("ACCEPTED"));

        mockMvc.perform(authenticated(post("/api/v1/sync/push"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("deviceId", deviceId, "events", List.of(productEvent)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("ALREADY_PROCESSED"))
                .andExpect(jsonPath("$.results[0].serverVersion").value(1));

        Map<String, Object> staleEvent = event(UUID.randomUUID().toString(), "PRODUCT", productId, 0,
                Map.of("productId", productId, "code", "TAB-001", "name", "Edición desconectada"));
        mockMvc.perform(authenticated(post("/api/v1/sync/push"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("deviceId", deviceId, "events", List.of(staleEvent)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("CONFLICT"));

        mockMvc.perform(authenticated(get("/api/v1/sync/pull").param("after", "0").param("limit", "300")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextCursor").value(2))
                .andExpect(jsonPath("$.changes.length()").value(2));

        mockMvc.perform(get("/admin/products/new").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/products")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .param("code", "WEB-001")
                        .param("name", "Producto creado en PC")
                        .param("description", "Debe llegar a la tablet")
                        .param("company", "Empresa Demo")
                        .param("brand", "Marca Demo")
                        .param("category", "General")
                        .param("status", "ACTIVE")
                        .param("version", "0"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/products"));

        mockMvc.perform(get("/admin/products").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());

        mockMvc.perform(authenticated(get("/api/v1/sync/pull").param("after", "2").param("limit", "300")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes[0].entityType").value("PRODUCT"))
                .andExpect(jsonPath("$.changes[0].payload.code").value("WEB-001"));

        mockMvc.perform(authenticated(get("/api/v1/sync/bootstrap").param("page", "0").param("limit", "300")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records.length()").value(3));

        assertThat(recordRepository.count()).isEqualTo(3);
        assertThat(changeRepository.count()).isEqualTo(3);
        assertThat(productRepository.count()).isEqualTo(2);
        assertThat(conflictRepository.count()).isEqualTo(1);
        assertThat(eventRepository.count()).isEqualTo(3);
    }

    @Test
    void rejectsRequestsWithoutDeviceCredentials() throws Exception {
        mockMvc.perform(get("/api/v1/sync/status"))
                .andExpect(status().isUnauthorized());
    }

    private Map<String, Object> event(String eventId, String type, String entityId, long baseVersion,
                                      Map<String, Object> payload) {
        return Map.of(
                "eventId", eventId,
                "entityType", type,
                "entityId", entityId,
                "operation", "UPSERT",
                "baseVersion", baseVersion,
                "occurredAt", Instant.now().toString(),
                "payload", payload
        );
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authenticated(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) {
        return request.header("X-Device-Id", deviceId).header("X-Device-Token", token);
    }
}
