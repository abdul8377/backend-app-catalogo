package com.abdul.catalogo.synchronization.entity;

import com.abdul.catalogo.synchronization.model.SyncOperation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
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
@Table(name = "sync_change_log", indexes = @Index(name = "idx_change_sequence", columnList = "sequence"))
public class ChangeLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sequence")
    private Long sequence;

    @Column(name = "entity_type", nullable = false, length = 80)
    private String entityType;

    @Column(name = "entity_id", nullable = false, length = 160)
    private String entityId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SyncOperation operation;

    @Column(nullable = false)
    private long version;

    @Column(name = "origin_device_id", length = 36, columnDefinition = "CHAR(36)")
    private String originDeviceId;

    @Column(name = "conflict_id", length = 36, columnDefinition = "CHAR(36)")
    private String conflictId;

    @Lob
    @Column(name = "payload_json", nullable = false, columnDefinition = "LONGTEXT")
    private String payloadJson;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;
}
