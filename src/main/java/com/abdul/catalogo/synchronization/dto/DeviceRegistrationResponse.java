package com.abdul.catalogo.synchronization.dto;

import java.time.Instant;

public record DeviceRegistrationResponse(
        String deviceId,
        String token,
        String apiContractVersion,
        String bootstrapStatus,
        Instant registeredAt
) {
}
