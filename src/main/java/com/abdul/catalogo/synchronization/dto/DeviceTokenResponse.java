package com.abdul.catalogo.synchronization.dto;

import java.time.Instant;

public record DeviceTokenResponse(String deviceId, String token, Instant rotatedAt) {
}
