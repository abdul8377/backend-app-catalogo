package com.abdul.catalogo.synchronization.dto;

import java.time.Instant;

public record PairingCodeResponse(
        String pairingId,
        String pairingCode,
        Instant expiresAt,
        String serverId,
        String serverName,
        String serviceType,
        String apiContractVersion,
        String qrPayload,
        String qrImageDataUrl
) {
}
