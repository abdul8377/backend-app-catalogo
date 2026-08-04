package com.abdul.catalogo.synchronization.entity;

import com.abdul.catalogo.synchronization.model.ConflictStatus;
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

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "sync_conflicts")
public class SyncConflictEntity {

    @Id
    @Column(length = 36, columnDefinition = "CHAR(36)")
    private String id;

    @Column(name = "entity_type", nullable = false, length = 80)
    private String entityType;

    @Column(name = "entity_id", nullable = false, length = 160)
    private String entityId;

    @Column(name = "server_version", nullable = false)
    private long serverVersion;

    @Column(name = "client_base_version", nullable = false)
    private long clientBaseVersion;

    @Lob
    @Column(name = "server_payload", nullable = false, columnDefinition = "LONGTEXT")
    private String serverPayload;

    @Lob
    @Column(name = "client_payload", nullable = false, columnDefinition = "LONGTEXT")
    private String clientPayload;

    @Column(name = "origin_device_id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private String originDeviceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ConflictStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by", length = 120)
    private String resolvedBy;

    @Column(name = "resolution", length = 40)
    private String resolution;

    @Lob
    @Column(name = "resolution_payload", columnDefinition = "LONGTEXT")
    private String resolutionPayload;

    @Column(name = "resolution_event_id", length = 36, columnDefinition = "CHAR(36)")
    private String resolutionEventId;
}
