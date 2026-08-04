package com.abdul.catalogo.synchronization.entity;

import com.abdul.catalogo.shared.persistence.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
        name = "sync_records",
        uniqueConstraints = @UniqueConstraint(name = "uq_sync_record_entity", columnNames = {"entity_type", "entity_id"}),
        indexes = {
                @Index(name = "idx_sync_record_entity", columnList = "entity_type,entity_id"),
                @Index(name = "idx_sync_record_updated", columnList = "updated_at")
        }
)
public class SyncRecordEntity extends AuditedEntity {

    @Id
    @Column(length = 36, columnDefinition = "CHAR(36)")
    private String id;

    @Column(name = "entity_type", nullable = false, length = 80)
    private String entityType;

    @Column(name = "entity_id", nullable = false, length = 160)
    private String entityId;

    @Lob
    @Column(name = "payload_json", nullable = false, columnDefinition = "LONGTEXT")
    private String payloadJson;

    @Column(nullable = false)
    private long version;

    @Column(nullable = false)
    private boolean deleted;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "origin_device_id", length = 36, columnDefinition = "CHAR(36)")
    private String originDeviceId;

    @Column(name = "last_sequence", nullable = false)
    private long lastSequence;

    @Version
    @Column(name = "technical_version", nullable = false)
    private long technicalVersion;
}
