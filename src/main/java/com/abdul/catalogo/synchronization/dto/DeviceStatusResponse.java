package com.abdul.catalogo.synchronization.dto;

import com.abdul.catalogo.synchronization.model.DeviceStatus;

import java.time.Instant;

public record DeviceStatusResponse(
        String deviceId,
        String name,
        String platform,
        String appVersion,
        String apiContractVersion,
        DeviceStatus status,
        Instant lastSeenAt,
        long lastDeliveredCursor,
        long lastAcknowledgedCursor,
        Instant lastPushAt,
        Instant lastPullAt,
        String lastError
) {
}
