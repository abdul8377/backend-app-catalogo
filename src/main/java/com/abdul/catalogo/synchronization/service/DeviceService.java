package com.abdul.catalogo.synchronization.service;

import com.abdul.catalogo.shared.exception.ResourceNotFoundException;
import com.abdul.catalogo.synchronization.dto.DeviceRegistrationRequest;
import com.abdul.catalogo.synchronization.dto.DeviceRegistrationResponse;
import com.abdul.catalogo.synchronization.dto.DeviceStatusResponse;
import com.abdul.catalogo.synchronization.entity.DeviceEntity;
import com.abdul.catalogo.synchronization.entity.DeviceSyncStateEntity;
import com.abdul.catalogo.synchronization.model.DeviceStatus;
import com.abdul.catalogo.synchronization.repository.DeviceRepository;
import com.abdul.catalogo.synchronization.repository.DeviceSyncStateRepository;
import com.abdul.catalogo.synchronization.security.DevicePrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

@Service
public class DeviceService {

    private final DeviceRepository deviceRepository;
    private final DeviceSyncStateRepository stateRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public DeviceService(DeviceRepository deviceRepository, DeviceSyncStateRepository stateRepository) {
        this.deviceRepository = deviceRepository;
        this.stateRepository = stateRepository;
    }

    @Transactional
    public DeviceRegistrationResponse register(DeviceRegistrationRequest request) {
        byte[] tokenBytes = new byte[32];
        secureRandom.nextBytes(tokenBytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);

        DeviceEntity device = new DeviceEntity();
        device.setId(UUID.randomUUID().toString());
        device.setName(request.name().trim());
        device.setPlatform(request.platform().trim());
        device.setTokenHash(hash(rawToken));
        device.setStatus(DeviceStatus.ACTIVE);
        device = deviceRepository.save(device);

        DeviceSyncStateEntity state = new DeviceSyncStateEntity();
        state.setDeviceId(device.getId());
        state.setLastPullCursor(0);
        stateRepository.save(state);

        return new DeviceRegistrationResponse(device.getId(), rawToken, device.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public Optional<DevicePrincipal> authenticate(String deviceId, String rawToken) {
        if (deviceId == null || rawToken == null || deviceId.isBlank() || rawToken.isBlank()) {
            return Optional.empty();
        }
        return deviceRepository.findById(deviceId)
                .filter(device -> device.getStatus() == DeviceStatus.ACTIVE)
                .filter(device -> MessageDigest.isEqual(
                        device.getTokenHash().getBytes(StandardCharsets.US_ASCII),
                        hash(rawToken).getBytes(StandardCharsets.US_ASCII)))
                .map(device -> new DevicePrincipal(device.getId(), device.getName()));
    }

    @Transactional(readOnly = true)
    public DeviceStatusResponse status(String deviceId) {
        DeviceEntity device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("DEVICE_NOT_FOUND", "El dispositivo no existe."));
        DeviceSyncStateEntity state = stateRepository.findById(deviceId).orElseGet(() -> {
            DeviceSyncStateEntity empty = new DeviceSyncStateEntity();
            empty.setLastPullCursor(0);
            return empty;
        });
        return new DeviceStatusResponse(device.getId(), device.getName(), device.getPlatform(), device.getStatus(),
                device.getLastSeenAt(), state.getLastPullCursor(), state.getLastPushAt(), state.getLastPullAt());
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
        stateRepository.save(state);
    }

    @Transactional
    public void markPulled(String deviceId, long cursor) {
        touch(deviceId);
        DeviceSyncStateEntity state = stateRepository.findById(deviceId).orElseGet(() -> newState(deviceId));
        state.setLastPullCursor(Math.max(state.getLastPullCursor(), cursor));
        state.setLastPullAt(Instant.now());
        stateRepository.save(state);
    }

    private DeviceSyncStateEntity newState(String deviceId) {
        DeviceSyncStateEntity state = new DeviceSyncStateEntity();
        state.setDeviceId(deviceId);
        state.setLastPullCursor(0);
        return state;
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no está disponible.", exception);
        }
    }
}
