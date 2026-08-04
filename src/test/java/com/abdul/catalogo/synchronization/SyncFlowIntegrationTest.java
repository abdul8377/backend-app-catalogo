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
        String pairing = mockMvc.perform(post("/admin/pairing-codes").with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String pairingCode = objectMapper.readTree(pairing).get("pairingCode").asText();
        String response = mockMvc.perform(post("/api/v1/devices/register").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Tablet de pruebas", "platform", "android",
                                "pairingCode", pairingCode, "appVersion", "1.0.0", "apiContractVersion", "1.0"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode registration = objectMapper.readTree(response);
        deviceId = registration.get("deviceId").asText(); token = registration.get("token").asText();
    }

    @Test
    void synchronizesAllEntityTypesIdempotentlyAndPublishesWebProducts() throws Exception {
        long initialRecords = recordRepository.count();
        long initialChanges = changeRepository.count();
        long initialProducts = productRepository.count();
        long initialConflicts = conflictRepository.count();
        long initialEvents = eventRepository.count();
        String companyId = UUID.randomUUID().toString(); String productId = UUID.randomUUID().toString();
        Map<String, Object> companyEvent = event(UUID.randomUUID().toString(), "COMPANY", companyId, 0,
                Map.of("syncId", companyId, "name", "Empresa Demo"));
        Map<String, Object> productPayload = productPayload(productId, "TAB-001", "Taladro de prueba");
        Map<String, Object> productEvent = event(UUID.randomUUID().toString(), "PRODUCT", productId, 0, productPayload);

        mockMvc.perform(authenticated(post("/api/v1/sync/push")).contentType(MediaType.APPLICATION_JSON)
                        .content(push(companyEvent, productEvent))).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$.results[0].version").value(1))
                .andExpect(jsonPath("$.results[0].sequence").isNumber())
                .andExpect(jsonPath("$.results[0].serverVersion").doesNotExist())
                .andExpect(jsonPath("$.results[0].serverSequence").doesNotExist())
                .andExpect(jsonPath("$.results[1].status").value("ACCEPTED"));
        mockMvc.perform(authenticated(post("/api/v1/sync/push")).contentType(MediaType.APPLICATION_JSON)
                        .content(push(productEvent))).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("ALREADY_PROCESSED"));

        Map<String, Object> stale = event(UUID.randomUUID().toString(), "PRODUCT", productId, 0,
                productPayload(productId, "TAB-001", "Edición desconectada"));
        mockMvc.perform(authenticated(post("/api/v1/sync/push")).contentType(MediaType.APPLICATION_JSON)
                        .content(push(stale))).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("CONFLICT"))
                .andExpect(jsonPath("$.results[0].conflictId").isNotEmpty());

        mockMvc.perform(get("/admin/products/{id}/edit", productId).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/admin/products/{id}", productId).with(user("admin").roles("ADMIN")).with(csrf())
                        .param("code", "TAB-001").param("name", "Taladro actualizado en PC")
                        .param("description", "Debe llegar completo a la tablet").param("company", "Empresa Demo")
                        .param("companyId", "company-demo").param("brand", "Marca Demo").param("brandId", "brand-demo")
                        .param("category", "Taladros").param("categoryId", "category-demo").param("subcategory", "")
                        .param("subcategoryId", "").param("productType", "SINGLE").param("status", "ACTIVE")
                        .param("version", "1").param("attributesJson", "{}")
                        .param("variantsJson", "[{\"sku\":\"TAB-001\",\"shortName\":\"Taladro PC\",\"status\":\"ACTIVE\",\"attributes\":{}}]")
                        .param("presentationsJson", "[]")
                        .param("pricesJson", "[{\"sku\":\"TAB-001\",\"priceList\":\"General\",\"currency\":\"PEN\",\"price\":149.90,\"quoteRequired\":false}]")
                        .param("imagesJson", "[]"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/products"));

        long expectedCursor = initialChanges + 3;
        String pullJson = mockMvc.perform(authenticated(get("/api/v1/sync/pull"))
                        .param("after", Long.toString(initialChanges)).param("limit", "300"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nextCursor").value(expectedCursor))
                .andExpect(jsonPath("$.changes.length()").value(3))
                .andReturn().getResponse().getContentAsString();
        JsonNode webUpdate = objectMapper.readTree(pullJson).path("changes").valueStream()
                .filter(change -> productId.equals(change.path("entityId").asText()) && change.path("version").asLong() == 2)
                .findFirst().orElseThrow();
        assertThat(webUpdate.path("payload").propertyNames()).containsExactlyInAnyOrder(
                "productId", "code", "name", "description", "company", "companyId", "brand", "brandId",
                "category", "categoryId", "subcategory", "subcategoryId", "productType", "status",
                "attributes", "variants", "presentations", "prices", "images");
        assertThat(webUpdate.path("payload").path("name").asText()).isEqualTo("Taladro actualizado en PC");
        assertThat(webUpdate.path("payload").path("variants").get(0).path("shortName").asText()).isEqualTo("Taladro PC");
        assertThat(webUpdate.path("payload").path("prices").get(0).path("price").decimalValue())
                .isEqualByComparingTo("149.90");
        mockMvc.perform(authenticated(post("/api/v1/sync/pull/ack")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cursor\":" + expectedCursor + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.acknowledgedCursor").value(expectedCursor));
        mockMvc.perform(authenticated(get("/api/v1/sync/pull"))
                        .param("after", Long.toString(expectedCursor)).param("limit", "300"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.changes.length()").value(0))
                .andExpect(jsonPath("$.nextCursor").value(expectedCursor));

        mockMvc.perform(get("/admin/products/new").with(user("admin").roles("ADMIN"))).andExpect(status().isOk());
        mockMvc.perform(post("/admin/products").with(user("admin").roles("ADMIN")).with(csrf())
                        .param("code", "WEB-" + UUID.randomUUID().toString().substring(0, 8)).param("name", "Producto creado en PC")
                        .param("description", "Debe llegar a la tablet").param("company", "Empresa Demo")
                        .param("brand", "Marca Demo").param("category", "General").param("subcategory", "")
                        .param("productType", "SINGLE").param("status", "ACTIVE").param("version", "0")
                        .param("attributesJson", "{}").param("variantsJson", "[{\"sku\":\"WEB-SKU\",\"attributes\":{}}]")
                        .param("presentationsJson", "[]").param("pricesJson", "[]").param("imagesJson", "[]"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/products"));

        mockMvc.perform(authenticated(get("/api/v1/sync/bootstrap")).param("page", "0").param("limit", "300"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.snapshotCursor").isNumber());
        assertThat(recordRepository.count()).isEqualTo(initialRecords + 3);
        assertThat(changeRepository.count()).isEqualTo(initialChanges + 4);
        assertThat(productRepository.count()).isEqualTo(initialProducts + 2);
        assertThat(conflictRepository.count()).isEqualTo(initialConflicts + 1);
        assertThat(eventRepository.count()).isEqualTo(initialEvents + 3);
    }

    @Test
    void rejectsRequestsWithoutDeviceCredentials() throws Exception {
        mockMvc.perform(get("/api/v1/sync/status")).andExpect(status().isUnauthorized());
    }

    @Test
    void statusUsesTheFrozenInitializationFieldNames() throws Exception {
        mockMvc.perform(authenticated(get("/api/v1/sync/status")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serverId").isNotEmpty())
                .andExpect(jsonPath("$.apiContractVersion").value("1.0"))
                .andExpect(jsonPath("$.recordCount").isNumber())
                .andExpect(jsonPath("$.changeCount").isNumber())
                .andExpect(jsonPath("$.pendingConflictCount").isNumber())
                .andExpect(jsonPath("$.records").doesNotExist())
                .andExpect(jsonPath("$.changes").doesNotExist());
    }

    private Map<String, Object> productPayload(String id, String code, String name) {
        return Map.ofEntries(Map.entry("productId", id), Map.entry("code", code), Map.entry("name", name),
                Map.entry("description", ""), Map.entry("company", "Empresa Demo"), Map.entry("companyId", "company-demo"),
                Map.entry("brand", "Marca Demo"), Map.entry("brandId", "brand-demo"),
                Map.entry("category", "Taladros"), Map.entry("categoryId", "category-demo"),
                Map.entry("subcategory", ""), Map.entry("subcategoryId", ""),
                Map.entry("status", "ACTIVE"), Map.entry("productType", "SINGLE"), Map.entry("attributes", Map.of()),
                Map.entry("variants", List.of(Map.of("sku", code, "shortName", name, "status", "ACTIVE", "attributes", Map.of()))),
                Map.entry("presentations", List.of()), Map.entry("prices", List.of()), Map.entry("images", List.of()));
    }

    private Map<String, Object> event(String id, String type, String entityId, long base, Map<String, Object> payload) {
        return Map.ofEntries(Map.entry("eventId", id), Map.entry("entityType", type), Map.entry("entityId", entityId),
                Map.entry("operation", "UPSERT"), Map.entry("baseVersion", base), Map.entry("payloadVersion", 1),
                Map.entry("schemaVersion", "1.0"), Map.entry("occurredAt", Instant.now().toString()), Map.entry("payload", payload));
    }

    @SafeVarargs
    private final String push(Map<String, Object>... events) throws Exception {
        return objectMapper.writeValueAsString(Map.of("deviceId", deviceId, "apiContractVersion", "1.0", "events", List.of(events)));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authenticated(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) {
        return request.header("X-Device-Id", deviceId).header("X-Device-Token", token);
    }
}
