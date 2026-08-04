package com.abdul.catalogo.synchronization.dto;

import jakarta.validation.constraints.Min;

public record SyncPullAckRequest(@Min(0) long cursor) {
}
