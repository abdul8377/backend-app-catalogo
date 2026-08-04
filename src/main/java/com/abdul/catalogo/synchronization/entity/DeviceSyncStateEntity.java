package com.abdul.catalogo.synchronization.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "sync_device_state")
public class DeviceSyncStateEntity {

    @Id
    @Column(name = "device_id", length = 36, columnDefinition = "CHAR(36)")
    private String deviceId;

    @Column(name = "last_pull_cursor", nullable = false)
    private long lastPullCursor;

    @Column(name = "last_push_at")
    private Instant lastPushAt;

    @Column(name = "last_pull_at")
    private Instant lastPullAt;
}
