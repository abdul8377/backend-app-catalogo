package com.abdul.catalogo.synchronization;

import com.abdul.catalogo.synchronization.dto.DeviceRegistrationRequest;
import com.abdul.catalogo.synchronization.dto.DeviceRegistrationResponse;
import com.abdul.catalogo.synchronization.dto.SyncEventRequest;
import com.abdul.catalogo.synchronization.dto.SyncPushRequest;
import com.abdul.catalogo.synchronization.model.ConflictResolution;
import com.abdul.catalogo.synchronization.model.SyncOperation;
import com.abdul.catalogo.synchronization.model.SyncResultStatus;
import com.abdul.catalogo.synchronization.repository.ChangeLogRepository;
import com.abdul.catalogo.synchronization.repository.PairingCodeRepository;
import com.abdul.catalogo.synchronization.service.DeviceService;
import com.abdul.catalogo.synchronization.service.PairingCodeService;
import com.abdul.catalogo.synchronization.service.SyncConflictService;
import com.abdul.catalogo.synchronization.service.SyncPushService;
import com.abdul.catalogo.synchronization.service.SyncReadService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class SecureSyncContractIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired PairingCodeService pairingCodeService;
    @Autowired PairingCodeRepository pairingCodeRepository;
    @Autowired DeviceService deviceService;
    @Autowired SyncPushService pushService;
    @Autowired SyncReadService readService;
    @Autowired ChangeLogRepository changeLogRepository;
    @Autowired SyncConflictService conflictService;
    @Autowired JdbcTemplate jdbc;

    @Test
    void pairingCodeIsRequiredSingleUseAndRevocationInvalidatesToken() throws Exception {
        mockMvc.perform(post("/api/v1/devices/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registrationJson("00000000")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_PAIRING_CODE"));

        var pairing = pairingCodeService.create("admin-test");
        var qr = objectMapper.readTree(pairing.qrPayload());
        assertThat(qr.propertyNames()).containsExactlyInAnyOrder(
                "serverId", "serverName", "pairingCode", "serviceType", "apiContractVersion");
        assertThat(qr.has("host")).isFalse();
        assertThat(qr.has("ip")).isFalse();
        String registration = mockMvc.perform(post("/api/v1/devices/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registrationJson(pairing.pairingCode())))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.bootstrapStatus").value("REQUIRED"))
                .andReturn().getResponse().getContentAsString();
        var registered = objectMapper.readTree(registration);
        String deviceId = registered.get("deviceId").asText();
        String token = registered.get("token").asText();

        mockMvc.perform(post("/api/v1/devices/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registrationJson(pairing.pairingCode())))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PAIRING_CODE_ALREADY_USED"));

        mockMvc.perform(post("/admin/devices/{id}/revoke", deviceId).with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/sync/status").header("X-Device-Id", deviceId).header("X-Device-Token", token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredPairingCodeAndUndeliveredAckAreRejected() {
        var pairing = pairingCodeService.create("admin-test");
        var entity = pairingCodeRepository.findById(pairing.pairingId()).orElseThrow();
        entity.setExpiresAt(Instant.now().minusSeconds(1));
        pairingCodeRepository.saveAndFlush(entity);
        assertThatThrownBy(() -> deviceService.register(new DeviceRegistrationRequest("Tablet", "android",
                pairing.pairingCode(), "1.0", "1.0"))).hasMessageContaining("expiró");

        DeviceRegistrationResponse device = register("ack");
        assertThatThrownBy(() -> deviceService.acknowledgePull(device.deviceId(), 1))
                .hasMessageContaining("no fue entregado");
        deviceService.markDelivered(device.deviceId(), 5);
        assertThat(deviceService.acknowledgePull(device.deviceId(), 5)).isEqualTo(5);
        assertThatThrownBy(() -> deviceService.acknowledgePull(device.deviceId(), 4)).hasMessageContaining("retroceder");
    }

    @Test
    void onlyOneConcurrentWriteWithTheSameBaseVersionIsAccepted() throws Exception {
        DeviceRegistrationResponse first = register("concurrent-a");
        DeviceRegistrationResponse second = register("concurrent-b");
        String entityId = UUID.randomUUID().toString();
        SyncEventRequest a = event(UUID.randomUUID().toString(), entityId, "Empresa A");
        SyncEventRequest b = event(UUID.randomUUID().toString(), entityId, "Empresa B");
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<SyncResultStatus> taskA = () -> pushService.push(first.deviceId(),
                    new SyncPushRequest(first.deviceId(), "1.0", List.of(a))).results().get(0).status();
            Callable<SyncResultStatus> taskB = () -> pushService.push(second.deviceId(),
                    new SyncPushRequest(second.deviceId(), "1.0", List.of(b))).results().get(0).status();
            var results = pool.invokeAll(List.of(taskA, taskB)).stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            }).toList();
            assertThat(results).containsExactlyInAnyOrder(SyncResultStatus.ACCEPTED, SyncResultStatus.CONFLICT);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void eventIdCannotBeReusedWithDifferentContent() {
        DeviceRegistrationResponse device = register("event-reuse");
        String eventId = UUID.randomUUID().toString();
        var first = pushService.push(device.deviceId(), new SyncPushRequest(device.deviceId(), "1.0",
                List.of(event(eventId, UUID.randomUUID().toString(), "Original"))));
        var second = pushService.push(device.deviceId(), new SyncPushRequest(device.deviceId(), "1.0",
                List.of(event(eventId, UUID.randomUUID().toString(), "Alterado"))));
        assertThat(first.results().get(0).status()).isEqualTo(SyncResultStatus.ACCEPTED);
        assertThat(second.results().get(0).status()).isEqualTo(SyncResultStatus.REJECTED);
        assertThat(second.results().get(0).message()).contains("contenido diferente");
    }

    @Test
    void pullPaginatesAndBootstrapUsesDependencyOrderWithTombstones() {
        DeviceRegistrationResponse device = register("pagination");
        long after = changeLogRepository.findTopByOrderBySequenceDesc() == null ? 0
                : changeLogRepository.findTopByOrderBySequenceDesc().getSequence();
        String companyId = UUID.randomUUID().toString();
        String brandId = UUID.randomUUID().toString();
        String categoryId = UUID.randomUUID().toString();
        SyncEventRequest company = event(UUID.randomUUID().toString(), "COMPANY", companyId, 0,
                SyncOperation.UPSERT, Map.of("id", companyId, "name", "Empresa"));
        SyncEventRequest category = event(UUID.randomUUID().toString(), "CATEGORY", categoryId, 0,
                SyncOperation.UPSERT, Map.of("id", categoryId, "name", "Categoría"));
        SyncEventRequest brand = event(UUID.randomUUID().toString(), "BRAND", brandId, 0,
                SyncOperation.UPSERT, Map.of("id", brandId, "company_id", companyId, "name", "Marca"));
        var created = pushService.push(device.deviceId(),
                new SyncPushRequest(device.deviceId(), "1.0", List.of(company, category, brand)));
        assertThat(created.results()).extracting(result -> result.status())
                .containsOnly(SyncResultStatus.ACCEPTED);
        SyncEventRequest tombstone = event(UUID.randomUUID().toString(), "CATEGORY", categoryId, 1,
                SyncOperation.DELETE, Map.of("id", categoryId, "name", "Categoría"));
        pushService.push(device.deviceId(), new SyncPushRequest(device.deviceId(), "1.0", List.of(tombstone)));

        var page1 = readService.pull(device.deviceId(), after, 2);
        assertThat(page1.hasMore()).isTrue();
        assertThat(page1.changes()).hasSize(2);
        readService.acknowledge(device.deviceId(), page1.nextCursor());
        var page2 = readService.pull(device.deviceId(), page1.nextCursor(), 2);
        assertThat(page2.hasMore()).isFalse();
        assertThat(page2.changes()).hasSize(2);

        var bootstrap = readService.bootstrap(0, 300, null);
        var ids = bootstrap.records().stream().map(record -> record.entityId()).toList();
        assertThat(ids.indexOf(companyId)).isLessThan(ids.indexOf(brandId));
        assertThat(ids.indexOf(brandId)).isLessThan(ids.indexOf(categoryId));
        assertThat(bootstrap.records().stream().filter(record -> record.entityId().equals(categoryId))
                .findFirst().orElseThrow().deleted()).isTrue();
    }

    @Test
    void relationalMastersKeepBusinessDataOutsideSyncRecords() {
        DeviceRegistrationResponse device = register("relational-master");
        String entityId = UUID.randomUUID().toString();
        var result = pushService.push(device.deviceId(), new SyncPushRequest(device.deviceId(), "1.0",
                List.of(event(UUID.randomUUID().toString(), "COMPANY", entityId, 0,
                        SyncOperation.UPSERT, Map.of("id", entityId, "name", "Empresa relacional")))))
                .results().get(0);

        assertThat(result.status()).isEqualTo(SyncResultStatus.ACCEPTED);
        assertThat(jdbc.queryForObject("SELECT nombre FROM empresas WHERE id = ?", String.class, entityId))
                .isEqualTo("Empresa relacional");
        assertThat(jdbc.queryForObject(
                "SELECT payload_json FROM sync_records WHERE entity_type = 'COMPANY' AND entity_id = ?",
                String.class, entityId)).isEqualTo("{}");
        assertThat(readService.bootstrap(0, 300, null).records())
                .anyMatch(record -> record.entityId().equals(entityId)
                        && record.payload().path("nombre").asText().equals("Empresa relacional"));
    }

    @Test
    void bootstrapKeepsTheRequestedSnapshotAndLaterChangesArriveByPull() {
        DeviceRegistrationResponse device = register("snapshot");
        String entityId = UUID.randomUUID().toString();
        var created = pushService.push(device.deviceId(), new SyncPushRequest(device.deviceId(), "1.0",
                List.of(event(UUID.randomUUID().toString(), "COMPANY", entityId, 0,
                        SyncOperation.UPSERT, "Inicial"))))
                .results().get(0);
        long snapshotCursor = created.sequence();
        assertThat(readService.bootstrap(0, 300, snapshotCursor).records())
                .anyMatch(record -> record.entityId().equals(entityId) && record.version() == 1);

        pushService.push(device.deviceId(), new SyncPushRequest(device.deviceId(), "1.0",
                List.of(event(UUID.randomUUID().toString(), "COMPANY", entityId, 1,
                        SyncOperation.UPSERT, "Modificada"))));

        assertThat(readService.bootstrap(0, 300, snapshotCursor).records())
                .anyMatch(record -> record.entityId().equals(entityId) && record.version() == 1
                        && record.payload().path("name").asText().equals("Inicial"));
        assertThat(readService.pull(device.deviceId(), snapshotCursor, 300).changes())
                .anyMatch(change -> change.entityId().equals(entityId) && change.version() == 2);
    }

    @Test
    void conflictIdSurvivesIdempotentReplayResolutionAndPull() {
        DeviceRegistrationResponse device = register("conflict-correlation");
        String entityId = UUID.randomUUID().toString();
        var accepted = pushService.push(device.deviceId(), new SyncPushRequest(device.deviceId(), "1.0",
                List.of(event(UUID.randomUUID().toString(), "COMPANY", entityId, 0,
                        SyncOperation.UPSERT, "Servidor"))))
                .results().get(0);
        String eventId = UUID.randomUUID().toString();
        SyncEventRequest stale = event(eventId, "COMPANY", entityId, 0, SyncOperation.UPSERT, "Tablet");
        var conflict = pushService.push(device.deviceId(),
                new SyncPushRequest(device.deviceId(), "1.0", List.of(stale))).results().get(0);
        var replay = pushService.push(device.deviceId(),
                new SyncPushRequest(device.deviceId(), "1.0", List.of(stale))).results().get(0);

        assertThat(conflict.conflictId()).isNotBlank();
        assertThat(replay.conflictId()).isEqualTo(conflict.conflictId());
        var resolved = conflictService.resolve(conflict.conflictId(), ConflictResolution.KEEP_SERVER, "", "admin");
        assertThat(resolved.conflictId()).isEqualTo(conflict.conflictId());
        assertThat(resolved.resolutionVersion()).isEqualTo(2);
        assertThat(resolved.resolutionSequence()).isGreaterThan(accepted.sequence());
        assertThat(readService.pull(device.deviceId(), accepted.sequence(), 300).changes())
                .anyMatch(change -> conflict.conflictId().equals(change.conflictId()) && change.version() == 2);
    }

    @Test
    void obsoleteProductPartsAndIncompatibleEventVersionsAreRejectedIndividually() {
        DeviceRegistrationResponse device = register("contract-rejections");
        String entityId = UUID.randomUUID().toString();
        var obsolete = event(UUID.randomUUID().toString(), "PRODUCT_VARIANT", entityId, 0,
                SyncOperation.UPSERT, "Variante antigua");
        var wrongVersion = new SyncEventRequest(UUID.randomUUID().toString(), "COMPANY", UUID.randomUUID().toString(),
                SyncOperation.UPSERT, 0, 2, "2.0", null, Instant.now(),
                objectMapper.valueToTree(Map.of("name", "Versión incompatible")));

        var response = pushService.push(device.deviceId(), new SyncPushRequest(device.deviceId(), "1.0",
                List.of(obsolete, wrongVersion)));

        assertThat(response.results()).extracting(result -> result.status())
                .containsExactly(SyncResultStatus.REJECTED, SyncResultStatus.REJECTED);
        assertThat(response.results().get(0).message()).contains("no está habilitada");
        assertThat(response.results().get(1).message()).contains("payloadVersion");
    }

    private DeviceRegistrationResponse register(String suffix) {
        var pairing = pairingCodeService.create("test");
        return deviceService.register(new DeviceRegistrationRequest("Tablet-" + suffix, "android",
                pairing.pairingCode(), "1.0.0", "1.0"));
    }

    private SyncEventRequest event(String eventId, String entityId, String name) {
        return event(eventId, "COMPANY", entityId, 0, SyncOperation.UPSERT, name);
    }

    private SyncEventRequest event(String eventId, String type, String entityId, long baseVersion,
                                   SyncOperation operation, String name) {
        return event(eventId, type, entityId, baseVersion, operation, Map.of("id", entityId, "name", name));
    }

    private SyncEventRequest event(String eventId, String type, String entityId, long baseVersion,
                                   SyncOperation operation, Map<String, ?> payload) {
        return new SyncEventRequest(eventId, type, entityId, operation, baseVersion, 1, "1.0", null,
                Instant.now(), objectMapper.valueToTree(payload));
    }

    private String registrationJson(String code) throws Exception {
        return objectMapper.writeValueAsString(Map.of("name", "Tablet segura", "platform", "android",
                "pairingCode", code, "appVersion", "1.0.0", "apiContractVersion", "1.0"));
    }
}
