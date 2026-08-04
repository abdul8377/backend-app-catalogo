package com.abdul.catalogo.synchronization.dto;

import java.util.List;

public record SyncPullResponse(long nextCursor, boolean hasMore, List<SyncChangeDto> changes) {
}
