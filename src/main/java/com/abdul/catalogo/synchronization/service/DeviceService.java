package com.abdul.catalogo.synchronization.service;

import com.abdul.catalogo.shared.config.ContractProperties;
import com.abdul.catalogo.shared.crypto.Digests;
import com.abdul.catalogo.shared.exception.BusinessRuleException;
import com.abdul.catalogo.shared.exception.ResourceNotFoundException;
import com.abdul.catalogo.synchronization.dto.DeviceRegistrationRequest;
import com.abdul.catalogo.synchronization.dto.DeviceRegistrationResponse;
import com.abdul.catalogo.synchronization.dto.DeviceStatusResponse;
import com.abdul.catalogo.synchronization.dto.DeviceTokenResponse;
import com.abdul.catalogo.synchronization.entity.DeviceEntity;
import com.abdul.catalogo.synchronization.entity.DeviceSyncStateEntity;
import com.abdul.catalogo.synchronization.entity.PairingCodeEntity;
import com.abdul.catalogo.synchronization.model.DeviceStatus;
import com.abdul.catalogo.synchronization.repository.DeviceRepository;
import com.abdul.catalogo.synchronization.repository.DeviceSyncStateRepository;
import com.abdul.catalogo.synchronization.security.DevicePrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class DeviceService {
    private final DeviceRepository deviceRepository;
    private final DeviceSyncStateRepository stateRepository;
    private final PairingCodeService pairingCodeService;
    private final DeviceAuditService auditService;
    private final ContractProperties contractProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    public DeviceService(DeviceRepository deviceRepository, DeviceSyncStateRepository stateRepository,
                         PairingCodeService pairingCodeService, DeviceAuditService auditService,
                         ContractProperties contractProperties) {
        this.deviceRepository = deviceRepository;
        this.stateRepository = stateRepository;
        this.pairingCodeService = pairingCodeService;
        this.auditService = auditService;
        this.contractProperties = contractProperties;
    }

    @Transactional
    public DeviceRegistrationResponse register(DeviceRegistrationRequest request) {
        requireCompatible(request.apiContractVersion());
        PairingCodeEntity pairing = pairingCodeService.requireAvailable(request.pairingCode());
        String rawToken = newToken();

        DeviceEntity device = new DeviceEntity();
        device.setId(UUID.randomUUID().toString());
        device.setName(request.name().trim());
        device.setPlatform(request.platform().trim());
        device.setAppVersion(request.appVersion().trim());
        device.setContractVersion(request.apiContractVersion().trim());
        device.setTokenHash(Digests.sha256(rawToken));
        device.setStatus(DeviceStatus.ACTIVE);
        device = deviceRepository.saveAndFlush(device);

        stateRepository.save(newState(device.getId()));
        pairingCodeService.markUsed(pairing, device.getId());
        auditService.record(device.getId(), "PAIRED", device.getName(),
                "platform=" + device.getPlatform() + ", appVersion=" + device.getAppVersion());
        return new DeviceRegistrationResponse(device.getId(), rawToken, contractProperties.version(),
                "REQUIRED", device.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public Optional<DevicePrincipal> authenticate(String deviceId, String rawToken) {
        if (deviceId == null || rawToken == null || deviceId.isBlank() || rawToken.isBlank()) return Optional.empty();
        return deviceRepository.findById(deviceId)
                .filter(device -> device.getStatus() == DeviceStatus.ACTIVE)
                .filter(device -> MessageDigest.isEqual(device.getTokenHash().getBytes(StandardCharsets.US_ASCII),
                        Digests.sha256(rawToken).getBytes(StandardCharsets.US_ASCII)))
                .map(device -> new DevicePrincipal(device.getId(), device.getName()));
    }

    @Transactional(readOnly = true)
    public DeviceStatusResponse status(String deviceId) {
        DeviceEntity device = requireDevice(deviceId);
        DeviceSyncStateEntity state = stateRepository.findById(deviceId).orElseGet(() -> newState(deviceId));
        return new DeviceStatusResponse(device.getId(), device.getName(), device.getPlatform(), device.getAppVersion(),
                device.getContractVersion(), device.getStatus(), device.getLastSeenAt(), state.getLastDeliveredCursor(),
                state.getLastAcknowledgedCursor(), state.getLastPushAt(), state.getLastPullAt(), state.getLastError());
    }

    @Transactional(readOnly = true)
    public List<DeviceStatusResponse> list() {
        return deviceRepository.findAllByOrderByCreatedAtDesc().stream().map(device -> status(device.getId())).toList();
    }

    @Transactional
    public void revoke(String deviceId, String actor) {
        DeviceEntity device = requireDevice(deviceId);
        device.setStatus(DeviceStatus.REVOKED);
        device.setRevokedAt(Instant.now());
        auditService.record(deviceId, "REVOKED", actor, "Acceso del dispositivo revocado");
    }

    @Transactional
    public DeviceTokenResponse rotateToken(String deviceId, String actor) {
        DeviceEntity device = requireDevice(deviceId);
        if (device.getStatus() != DeviceStatus.ACTIVE) {
            throw new BusinessRuleException("DEVICE_REVOKED", "No se puede rotar el token de un dispositivo revocado.");
        }
        String rawToken = newToken();
        Instant now = Instant.now();
        device.setTokenHash(Digests.sha256(rawToken));
        device.setTokenRotatedAt(now);
        auditService.record(deviceId, "TOKEN_ROTATED", actor, "Token rotado; el anterior quedó invalidado");
        return new DeviceTokenResponse(deviceId, rawToken, now);
    }

    @Transactional
    public void touch(String deviceId) {
        deviceRepository.findById(deviceId).ifPresent(device -> device.setLastSeenAt(Instant.now()));
    }

    @Transactional
    public void markPushed(String deviceId) {
        touch(deviceId);
        DeviceSyncStateEntity state = stateRepository.findById(deviceId).orElseGet(() -> newState(deviceId));
        state.setLastPushAt(Instant.now());
        state.setLastError(null);
        stateRepository.save(state);
    }

    @Transactional
    public void markDelivered(String deviceId, long cursor) {
        touch(deviceId);
        DeviceSyncStateEntity state = stateRepository.findById(deviceId).orElseGet(() -> newState(deviceId));
        state.setLastDeliveredCursor(Math.max(state.getLastDeliveredCursor(), cursor));
        state.setLastPullAt(Instant.now());
        state.setLastError(null);
        stateRepository.save(state);
    }

    @Transactional
    public long acknowledgePull(String deviceId, long cursor) {
        DeviceSyncStateEntity state = stateRepository.findById(deviceId).orElseGet(() -> newState(deviceId));
        if (cursor < state.getLastAcknowledgedCursor()) {
            throw new BusinessRuleException("PULL_ACK_REGRESSION", "El cursor confirmado no puede retroceder.");
        }
        if (cursor > state.getLastDeliveredCursor()) {
            throw new BusinessRuleException("PULL_ACK_NOT_DELIVERED", "No se puede confirmar un cursor que no fue entregado.");
        }
        state.setLastAcknowledgedCursor(cursor);
        state.setLastPullCursor(cursor);
        state.setLastError(null);
        stateRepository.save(state);
        touch(deviceId);
        return cursor;
    }

    @Transactional
    public void markPullError(String deviceId, String message) {
        DeviceSyncStateEntity state = stateRepository.findById(deviceId).orElseGet(() -> newState(deviceId));
        state.setLastError(message == null ? null : message.substring(0, Math.min(message.length(), 1000)));
        stateRepository.save(state);
    }

    private void requireCompatible(String requestedVersion) {
        if (!contractProperties.version().equals(requestedVersion)) {
            throw new BusinessRuleException("INCOMPATIBLE_API_CONTRACT",
                    "La app usa el contrato " + requestedVersion + " y el servidor requiere " + contractProperties.version() + ".");
        }
    }

    private DeviceEntity requireDevice(String id) {
        return deviceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("DEVICE_NOT_FOUND", "El dispositivo no existe."));
    }

    private DeviceSyncStateEntity newState(String deviceId) {
        DeviceSyncStateEntity state = new DeviceSyncStateEntity();
        state.setDeviceId(deviceId);
        state.setLastPullCursor(0);
        state.setLastDeliveredCursor(0);
        state.setLastAcknowledgedCursor(0);
        return state;
    }

    private String newToken() {
        byte[] tokenBytes = new byte[32];
        secureRandom.nextBytes(tokenBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    }
}
