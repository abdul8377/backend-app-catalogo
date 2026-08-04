package com.abdul.catalogo.synchronization.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DeviceRegistrationRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 40) String platform
) {
}
