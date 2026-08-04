package com.abdul.catalogo.storage.dto;

import com.abdul.catalogo.storage.model.StoredFileStatus;
import com.abdul.catalogo.storage.model.StoredFileType;
import com.abdul.catalogo.storage.model.FileVisibility;

import java.time.Instant;

public record FileIntentResponse(
        String fileId,
        String storageKey,
        StoredFileType fileType,
        String ownerType,
        String ownerId,
        String contentType,
        long sizeBytes,
        String checksumSha256,
        FileVisibility visibility,
        StoredFileStatus status,
        Instant expiresAt,
        String uploadUrl,
        String completeUrl
) {}
