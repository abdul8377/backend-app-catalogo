package com.abdul.catalogo.synchronization.service;

import com.abdul.catalogo.synchronization.entity.DeviceAuditLogEntity;
import com.abdul.catalogo.synchronization.repository.DeviceAuditLogRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class DeviceAuditService {
    private final DeviceAuditLogRepository repository;

    public DeviceAuditService(DeviceAuditLogRepository repository) {
        this.repository = repository;
    }

    public void record(String deviceId, String action, String actor, String details) {
        DeviceAuditLogEntity audit = new DeviceAuditLogEntity();
        audit.setId(UUID.randomUUID().toString());
        audit.setDeviceId(deviceId);
        audit.setAction(action);
        audit.setActor(actor == null || actor.isBlank() ? "system" : actor);
        audit.setDetails(details == null ? "" : details);
        audit.setOccurredAt(Instant.now());
        repository.save(audit);
    }
}
