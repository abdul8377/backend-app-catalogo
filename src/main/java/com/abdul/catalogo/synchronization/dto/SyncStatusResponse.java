package com.abdul.catalogo.synchronization.dto;

import java.time.Instant;
import java.util.Set;

public record SyncStatusResponse(
        String serverId,
        String apiContractVersion,
        long records,
        long changes,
        long processedEvents,
        long pendingConflicts,
        Set<String> supportedEntityTypes,
        Instant serverTime
) {
}
