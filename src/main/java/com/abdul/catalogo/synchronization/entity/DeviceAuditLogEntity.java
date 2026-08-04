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
@Table(name = "device_audit_log")
public class DeviceAuditLogEntity {
    @Id
    @Column(length = 36, columnDefinition = "CHAR(36)")
    private String id;

    @Column(name = "device_id", length = 36, columnDefinition = "CHAR(36)")
    private String deviceId;

    @Column(nullable = false, length = 40)
    private String action;

    @Column(nullable = false, length = 120)
    private String actor;

    @Column(nullable = false, length = 1000)
    private String details;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
}
