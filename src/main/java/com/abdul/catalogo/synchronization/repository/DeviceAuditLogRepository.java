package com.abdul.catalogo.synchronization.repository;

import com.abdul.catalogo.synchronization.entity.DeviceAuditLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceAuditLogRepository extends JpaRepository<DeviceAuditLogEntity, String> {
}
