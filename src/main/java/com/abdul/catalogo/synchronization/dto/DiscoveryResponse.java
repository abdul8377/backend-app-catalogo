package com.abdul.catalogo.synchronization.dto;

public record DiscoveryResponse(
        String serverId,
        String serverName,
        String serviceType,
        int port,
        String apiContractVersion,
        boolean pairingAvailable
) {
}
