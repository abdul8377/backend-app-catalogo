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
@Table(name = "server_identity")
public class ServerIdentityEntity {
    @Id
    @Column(name = "singleton_id")
    private short singletonId;

    @Column(name = "server_id", nullable = false, unique = true, length = 36, columnDefinition = "CHAR(36)")
    private String serverId;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
