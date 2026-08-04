package com.abdul.catalogo.storage.dto;

import com.abdul.catalogo.storage.model.FileVisibility;
import com.abdul.catalogo.storage.model.StoredFileStatus;
import com.abdul.catalogo.storage.model.StoredFileType;

import java.time.Instant;

public record StoredFileResponse(
        String fileId,
        String storageKey,
        StoredFileType fileType,
        String ownerType,
        String ownerId,
        String downloadUrl,
        String contentType,
        long sizeBytes,
        String checksumSha256,
        FileVisibility visibility,
        StoredFileStatus status,
        Instant createdAt,
        Instant expiresAt,
        Instant uploadedAt,
        Instant completedAt
) {}
