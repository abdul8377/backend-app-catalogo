package com.abdul.catalogo.storage.dto;

import com.abdul.catalogo.storage.model.FileVisibility;
import com.abdul.catalogo.storage.model.StoredFileStatus;

public record StoredFileResponse(String fileId, String storageKey, String downloadUrl, String contentType,
                                 long sizeBytes, String checksumSha256, FileVisibility visibility,
                                 StoredFileStatus status) {}
