package com.abdul.catalogo.synchronization.dto;

import com.abdul.catalogo.synchronization.model.DeviceStatus;

import java.time.Instant;

public record DeviceStatusResponse(
        String deviceId,
        String name,
        String platform,
        DeviceStatus status,
        Instant lastSeenAt,
        long lastPullCursor,
        Instant lastPushAt,
        Instant lastPullAt
) {
}
