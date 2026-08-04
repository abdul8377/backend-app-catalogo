package com.abdul.catalogo.synchronization.dto;

import java.util.List;

public record SyncPushResponse(List<SyncEventResult> results) {
}
