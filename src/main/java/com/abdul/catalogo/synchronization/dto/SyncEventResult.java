package com.abdul.catalogo.synchronization.dto;

import com.abdul.catalogo.synchronization.model.SyncResultStatus;

public record SyncEventResult(
        String eventId,
        SyncResultStatus status,
        Long serverVersion,
        Long serverSequence,
        String conflictId,
        String message
) {
}
