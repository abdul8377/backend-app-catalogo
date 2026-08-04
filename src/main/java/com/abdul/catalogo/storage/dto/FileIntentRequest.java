package com.abdul.catalogo.storage.dto;

import com.abdul.catalogo.storage.model.FileVisibility;
import com.abdul.catalogo.storage.model.StoredFileType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record FileIntentRequest(
        @NotBlank @Size(max = 255) String fileName,
        @NotNull StoredFileType fileType,
        @NotBlank @Size(max = 120) String contentType,
        @Min(1) long sizeBytes,
        @NotBlank @Pattern(regexp = "^[a-fA-F0-9]{64}$") String checksumSha256,
        @NotNull FileVisibility visibility,
        @NotBlank @Size(max = 80) String ownerType,
        @NotBlank @Size(max = 160) String ownerId
) {}
