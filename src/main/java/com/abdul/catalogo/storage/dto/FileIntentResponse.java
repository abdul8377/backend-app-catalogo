package com.abdul.catalogo.storage.dto;

import com.abdul.catalogo.storage.model.StoredFileStatus;

public record FileIntentResponse(String fileId, String uploadUrl, String completeUrl, StoredFileStatus status) {}
