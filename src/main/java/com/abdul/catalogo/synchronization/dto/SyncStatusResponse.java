package com.abdul.catalogo.synchronization.dto;

public record SyncStatusResponse(
        String serverId,
        String apiContractVersion,
        long recordCount,
        long changeCount,
        long pendingConflictCount
) {
}
