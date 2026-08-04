package com.abdul.catalogo.storage.entity;

import com.abdul.catalogo.storage.model.FileVisibility;
import com.abdul.catalogo.storage.model.StoredFileStatus;
import com.abdul.catalogo.storage.model.StoredFileType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter @Setter @NoArgsConstructor
@Entity @Table(name = "stored_files")
public class StoredFileEntity {
    @Id @Column(length = 36, columnDefinition = "CHAR(36)") private String id;
    @Column(name = "storage_key", nullable = false, unique = true, length = 500) private String storageKey;
    @Enumerated(EnumType.STRING) @Column(name = "file_type", nullable = false, length = 40) private StoredFileType fileType;
    @Column(name = "original_name", nullable = false, length = 255) private String originalName;
    @Column(name = "content_type", nullable = false, length = 120) private String contentType;
    @Column(name = "size_bytes", nullable = false) private long sizeBytes;
    @Column(name = "checksum_sha256", nullable = false, length = 64, columnDefinition = "CHAR(64)") private String checksumSha256;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private FileVisibility visibility;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private StoredFileStatus status;
    @Column(name = "owner_type", length = 80) private String ownerType;
    @Column(name = "owner_id", length = 160) private String ownerId;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "expires_at") private Instant expiresAt;
    @Column(name = "uploaded_at") private Instant uploadedAt;
    @Column(name = "completed_at") private Instant completedAt;
}
