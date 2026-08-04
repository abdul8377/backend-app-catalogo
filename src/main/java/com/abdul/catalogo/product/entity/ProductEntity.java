package com.abdul.catalogo.product.entity;

import com.abdul.catalogo.product.model.ProductStatus;
import com.abdul.catalogo.shared.persistence.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "products", indexes = {
        @Index(name = "idx_product_name", columnList = "name"),
        @Index(name = "idx_product_status", columnList = "status"),
        @Index(name = "idx_product_classification", columnList = "company,brand,category")
})
public class ProductEntity extends AuditedEntity {

    @Id
    @Column(length = 36, columnDefinition = "CHAR(36)")
    private String id;

    @Column(nullable = false, unique = true, length = 100)
    private String code;

    @Column(nullable = false, length = 250)
    private String name;

    @Column(nullable = false, length = 2000)
    private String description = "";

    @Column(nullable = false, length = 160)
    private String company = "";

    @Column(nullable = false, length = 160)
    private String brand = "";

    @Column(nullable = false, length = 160)
    private String category = "";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProductStatus status;

    @Column(nullable = false)
    private long version;

    @Lob
    @Column(name = "aggregate_json", nullable = false, columnDefinition = "LONGTEXT")
    private String aggregateJson;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "origin_device_id", length = 36, columnDefinition = "CHAR(36)")
    private String originDeviceId;
}
