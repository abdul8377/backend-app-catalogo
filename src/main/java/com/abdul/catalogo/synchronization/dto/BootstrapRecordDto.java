package com.abdul.catalogo.synchronization.dto;

import tools.jackson.databind.JsonNode;

import java.time.Instant;

public record BootstrapRecordDto(
        String entityType,
        String entityId,
        long version,
        boolean deleted,
        JsonNode payload,
        Instant updatedAt
) {
}
