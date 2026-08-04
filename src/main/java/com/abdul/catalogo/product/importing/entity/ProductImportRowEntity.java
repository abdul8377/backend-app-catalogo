package com.abdul.catalogo.product.importing.entity;

import com.abdul.catalogo.product.importing.model.ProductImportAction;
import com.abdul.catalogo.product.importing.model.ProductImportRowStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter @Setter @NoArgsConstructor
@Entity @Table(name = "product_import_rows")
public class ProductImportRowEntity {
    @Id @Column(length = 36, columnDefinition = "CHAR(36)") private String id;
    @Column(name = "import_id", nullable = false, length = 36, columnDefinition = "CHAR(36)") private String importId;
    @Column(name = "source_row_number", nullable = false) private int rowNumber;
    @Column(name = "family_code", nullable = false, length = 100) private String familyCode;
    @Column(name = "product_id", length = 36, columnDefinition = "CHAR(36)") private String productId;
    @Column(name = "expected_version") private Long expectedVersion;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private ProductImportAction action;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private ProductImportRowStatus status;
    @Lob @Column(name = "candidate_json", nullable = false, columnDefinition = "LONGTEXT") private String candidateJson;
    @Lob @Column(name = "messages_json", nullable = false, columnDefinition = "LONGTEXT") private String messagesJson;
    @Column(name = "result_product_id", length = 36, columnDefinition = "CHAR(36)") private String resultProductId;
    @Column(name = "result_version") private Long resultVersion;
    @Column(name = "processed_at") private Instant processedAt;
}
