package com.abdul.catalogo.synchronization.dto;

import com.abdul.catalogo.synchronization.model.SyncResultStatus;

public record SyncEventResult(
        String eventId,
        SyncResultStatus status,
        Long version,
        Long sequence,
        String conflictId,
        String message
) {
}
