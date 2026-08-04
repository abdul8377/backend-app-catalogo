package com.abdul.catalogo.synchronization.dto;

import com.abdul.catalogo.synchronization.model.SyncOperation;
import tools.jackson.databind.JsonNode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record SyncEventRequest(
        @NotBlank @Size(max = 36) String eventId,
        @NotBlank @Size(max = 80) String entityType,
        @NotBlank @Size(max = 160) String entityId,
        @NotNull SyncOperation operation,
        @Min(0) long baseVersion,
        @NotNull Instant occurredAt,
        @NotNull JsonNode payload
) {
}
