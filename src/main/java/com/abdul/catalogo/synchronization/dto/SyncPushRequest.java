package com.abdul.catalogo.synchronization.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record SyncPushRequest(
        @NotBlank String deviceId,
        @NotBlank String apiContractVersion,
        @NotEmpty List<@Valid SyncEventRequest> events
) {
}
