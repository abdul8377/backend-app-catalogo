package com.abdul.catalogo.synchronization.service;

import com.abdul.catalogo.product.service.ProductProjectionService;
import com.abdul.catalogo.shared.exception.BusinessRuleException;
import com.abdul.catalogo.synchronization.dto.SyncEventRequest;
import com.abdul.catalogo.synchronization.dto.SyncEventResult;
import com.abdul.catalogo.synchronization.entity.ChangeLogEntity;
import com.abdul.catalogo.synchronization.entity.ProcessedEventEntity;
import com.abdul.catalogo.synchronization.entity.SyncConflictEntity;
import com.abdul.catalogo.synchronization.entity.SyncRecordEntity;
import com.abdul.catalogo.synchronization.model.ConflictStatus;
import com.abdul.catalogo.synchronization.model.SyncOperation;
import com.abdul.catalogo.synchronization.model.SyncResultStatus;
import com.abdul.catalogo.synchronization.repository.ChangeLogRepository;
import com.abdul.catalogo.synchronization.repository.ProcessedEventRepository;
import com.abdul.catalogo.synchronization.repository.SyncConflictRepository;
import com.abdul.catalogo.synchronization.repository.SyncRecordRepository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class SyncEventProcessor {

    private final SyncEntityCatalog entityCatalog;
    private final SyncRecordRepository recordRepository;
    private final ProcessedEventRepository eventRepository;
    private final ChangeLogRepository changeRepository;
    private final SyncConflictRepository conflictRepository;
    private final ProductProjectionService productProjectionService;
    private final ObjectMapper objectMapper;

    public SyncEventProcessor(SyncEntityCatalog entityCatalog, SyncRecordRepository recordRepository,
                              ProcessedEventRepository eventRepository, ChangeLogRepository changeRepository,
                              SyncConflictRepository conflictRepository, ProductProjectionService productProjectionService,
                              ObjectMapper objectMapper) {
        this.entityCatalog = entityCatalog;
        this.recordRepository = recordRepository;
        this.eventRepository = eventRepository;
        this.changeRepository = changeRepository;
        this.conflictRepository = conflictRepository;
        this.productProjectionService = productProjectionService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public SyncEventResult process(String deviceId, SyncEventRequest event) {
        ProcessedEventEntity previous = eventRepository.findById(event.eventId()).orElse(null);
        if (previous != null) {
            return new SyncEventResult(event.eventId(), SyncResultStatus.ALREADY_PROCESSED,
                    previous.getServerVersion(), previous.getServerSequence(), previous.getMessage());
        }

        final String entityType;
        final String payloadJson;
        try {
            entityType = entityCatalog.normalizeAndValidate(event.entityType());
            payloadJson = objectMapper.writeValueAsString(event.payload());
            if (entityType.equals("PRODUCT") && event.operation() == SyncOperation.UPSERT) {
                productProjectionService.validateUpsert(event.entityId(), event.payload());
            }
        } catch (BusinessRuleException | JacksonException exception) {
            String message = exception.getMessage();
            saveProcessed(event.eventId(), deviceId, SyncResultStatus.REJECTED, null, null, message);
            return new SyncEventResult(event.eventId(), SyncResultStatus.REJECTED, null, null, message);
        }

        SyncRecordEntity record = recordRepository.findByEntityTypeAndEntityId(entityType, event.entityId()).orElse(null);
        long currentVersion = record == null ? 0 : record.getVersion();
        if (event.baseVersion() != currentVersion) {
            createConflict(deviceId, event, entityType, payloadJson, record, currentVersion);
            saveProcessed(event.eventId(), deviceId, SyncResultStatus.CONFLICT, currentVersion, null,
                    "La versión del servidor cambió desde la última sincronización.");
            return new SyncEventResult(event.eventId(), SyncResultStatus.CONFLICT, currentVersion, null,
                    "La versión del servidor cambió desde la última sincronización.");
        }

        if (record == null) {
            record = new SyncRecordEntity();
            record.setId(UUID.randomUUID().toString());
            record.setEntityType(entityType);
            record.setEntityId(event.entityId());
        }
        long nextVersion = currentVersion + 1;
        boolean deleted = event.operation() == SyncOperation.DELETE;
        record.setPayloadJson(payloadJson);
        record.setVersion(nextVersion);
        record.setDeleted(deleted);
        record.setDeletedAt(deleted ? Instant.now() : null);
        record.setOriginDeviceId(deviceId);
        recordRepository.save(record);

        if (entityType.equals("PRODUCT")) {
            productProjectionService.apply(event.entityId(), event.payload(), nextVersion, deviceId, deleted);
        }

        ChangeLogEntity change = new ChangeLogEntity();
        change.setEntityType(entityType);
        change.setEntityId(event.entityId());
        change.setOperation(event.operation());
        change.setVersion(nextVersion);
        change.setOriginDeviceId(deviceId);
        change.setPayloadJson(payloadJson);
        change.setChangedAt(Instant.now());
        change = changeRepository.saveAndFlush(change);

        saveProcessed(event.eventId(), deviceId, SyncResultStatus.ACCEPTED, nextVersion, change.getSequence(), null);
        return new SyncEventResult(event.eventId(), SyncResultStatus.ACCEPTED,
                nextVersion, change.getSequence(), null);
    }

    private void createConflict(String deviceId, SyncEventRequest event, String entityType, String clientPayload,
                                SyncRecordEntity record, long currentVersion) {
        SyncConflictEntity conflict = new SyncConflictEntity();
        conflict.setId(UUID.randomUUID().toString());
        conflict.setEntityType(entityType);
        conflict.setEntityId(event.entityId());
        conflict.setServerVersion(currentVersion);
        conflict.setClientBaseVersion(event.baseVersion());
        conflict.setServerPayload(record == null ? "{}" : record.getPayloadJson());
        conflict.setClientPayload(clientPayload);
        conflict.setOriginDeviceId(deviceId);
        conflict.setStatus(ConflictStatus.PENDING);
        conflict.setCreatedAt(Instant.now());
        conflictRepository.save(conflict);
    }

    private void saveProcessed(String eventId, String deviceId, SyncResultStatus status,
                               Long serverVersion, Long serverSequence, String message) {
        ProcessedEventEntity processed = new ProcessedEventEntity();
        processed.setEventId(eventId);
        processed.setDeviceId(deviceId);
        processed.setStatus(status);
        processed.setServerVersion(serverVersion);
        processed.setServerSequence(serverSequence);
        processed.setProcessedAt(Instant.now());
        processed.setMessage(message);
        eventRepository.save(processed);
    }
}
