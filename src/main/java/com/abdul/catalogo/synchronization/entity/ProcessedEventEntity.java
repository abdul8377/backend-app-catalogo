package com.abdul.catalogo.synchronization.entity;

import com.abdul.catalogo.synchronization.model.SyncResultStatus;
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

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "sync_processed_events")
public class ProcessedEventEntity {

    @Id
    @Column(name = "event_id", length = 36, columnDefinition = "CHAR(36)")
    private String eventId;

    @Column(name = "device_id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private String deviceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SyncResultStatus status;

    @Column(name = "server_version")
    private Long serverVersion;

    @Column(name = "server_sequence")
    private Long serverSequence;

    @Column(name = "conflict_id", length = 36, columnDefinition = "CHAR(36)")
    private String conflictId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    @Column(name = "message", length = 500)
    private String message;

    @Column(name = "request_checksum", length = 64, columnDefinition = "CHAR(64)")
    private String requestChecksum;

    @Column(name = "payload_version", nullable = false)
    private int payloadVersion;

    @Column(name = "schema_version", length = 30)
    private String schemaVersion;
}
