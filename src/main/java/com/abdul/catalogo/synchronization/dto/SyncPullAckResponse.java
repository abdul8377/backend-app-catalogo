package com.abdul.catalogo.synchronization.dto;

import java.time.Instant;

public record SyncPullAckResponse(long acknowledgedCursor, Instant acknowledgedAt) {
}
