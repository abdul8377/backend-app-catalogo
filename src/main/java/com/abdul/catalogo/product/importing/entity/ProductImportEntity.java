package com.abdul.catalogo.product.importing.entity;

import com.abdul.catalogo.product.importing.model.ProductImportStatus;
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
@Entity @Table(name = "product_imports")
public class ProductImportEntity {
    @Id @Column(length = 36, columnDefinition = "CHAR(36)")
    private String id;
    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;
    @Column(name = "file_hash", nullable = false, unique = true, length = 64, columnDefinition = "CHAR(64)")
    private String fileHash;
    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;
    @Column(name = "template_version", nullable = false, length = 20)
    private String templateVersion;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    private ProductImportStatus status;
    @Column(name = "total_rows", nullable = false) private int totalRows;
    @Column(name = "valid_rows", nullable = false) private int validRows;
    @Column(name = "warning_rows", nullable = false) private int warningRows;
    @Column(name = "error_rows", nullable = false) private int errorRows;
    @Column(name = "created_by", nullable = false, length = 120) private String createdBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "confirmed_at") private Instant confirmedAt;
}
