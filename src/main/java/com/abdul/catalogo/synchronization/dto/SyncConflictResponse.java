package com.abdul.catalogo.synchronization.dto;

import com.abdul.catalogo.synchronization.model.ConflictStatus;
import tools.jackson.databind.JsonNode;

import java.time.Instant;

public record SyncConflictResponse(String conflictId, String entityType, String entityId, long serverVersion,
                                   long clientBaseVersion, JsonNode serverPayload, JsonNode clientPayload,
                                   String originDeviceId, ConflictStatus status, Instant createdAt,
                                   Instant resolvedAt, String resolvedBy, String resolution) {}
