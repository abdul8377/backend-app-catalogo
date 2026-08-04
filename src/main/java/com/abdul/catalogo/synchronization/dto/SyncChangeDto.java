package com.abdul.catalogo.synchronization.dto;

import com.abdul.catalogo.synchronization.model.SyncOperation;
import tools.jackson.databind.JsonNode;

import java.time.Instant;

public record SyncChangeDto(
        long sequence,
        String entityType,
        String entityId,
        SyncOperation operation,
        long version,
        String originDeviceId,
        JsonNode payload,
        Instant changedAt
) {
}
