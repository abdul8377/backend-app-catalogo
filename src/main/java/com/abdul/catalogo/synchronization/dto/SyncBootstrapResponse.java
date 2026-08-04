package com.abdul.catalogo.synchronization.dto;

import java.util.List;

public record SyncBootstrapResponse(
        int page,
        int nextPage,
        boolean hasMore,
        long snapshotCursor,
        List<BootstrapRecordDto> records
) {
}
