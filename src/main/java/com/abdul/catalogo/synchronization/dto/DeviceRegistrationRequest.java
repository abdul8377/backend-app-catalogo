package com.abdul.catalogo.synchronization.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DeviceRegistrationRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 40) String platform,
        @NotBlank @Size(min = 6, max = 20) String pairingCode,
        @NotBlank @Size(max = 40) String appVersion,
        @NotBlank @Size(max = 20) String apiContractVersion
) {
}
