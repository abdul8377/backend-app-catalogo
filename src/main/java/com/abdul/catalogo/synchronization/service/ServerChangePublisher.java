package com.abdul.catalogo.synchronization.service;

import com.abdul.catalogo.synchronization.entity.ChangeLogEntity;
import com.abdul.catalogo.synchronization.entity.SyncRecordEntity;
import com.abdul.catalogo.synchronization.model.SyncOperation;
import com.abdul.catalogo.synchronization.repository.ChangeLogRepository;
import com.abdul.catalogo.synchronization.repository.SyncRecordRepository;
import com.abdul.catalogo.shared.exception.BusinessRuleException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class ServerChangePublisher {

    public record PublishedChange(long version, long sequence) {
    }

    private final SyncRecordRepository recordRepository;
    private final ChangeLogRepository changeRepository;
    private final ObjectMapper objectMapper;

    public ServerChangePublisher(SyncRecordRepository recordRepository, ChangeLogRepository changeRepository,
                                 ObjectMapper objectMapper) {
        this.recordRepository = recordRepository;
        this.changeRepository = changeRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public PublishedChange publish(String entityType, String entityId, SyncOperation operation,
                                   JsonNode payload, String origin, Long expectedVersion) {
        return publish(entityType, entityId, operation, payload, origin, expectedVersion, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public PublishedChange publish(String entityType, String entityId, SyncOperation operation,
                                   JsonNode payload, String origin, Long expectedVersion, String conflictId) {
        String normalizedType = entityType.trim().toUpperCase();
        SyncRecordEntity record = recordRepository.findForUpdate(normalizedType, entityId)
                .orElseGet(() -> newRecord(normalizedType, entityId));
        if (expectedVersion != null && record.getVersion() != expectedVersion) {
            throw new BusinessRuleException("ENTITY_VERSION_CONFLICT",
                    "La entidad cambió mientras se intentaba publicar. Recarga los datos y vuelve a intentarlo.");
        }
        long version = record.getVersion() + 1;
        String json = write(payload);
        record.setPayloadJson(json);
        record.setVersion(version);
        record.setDeleted(operation == SyncOperation.DELETE);
        record.setDeletedAt(operation == SyncOperation.DELETE ? Instant.now() : null);
        record.setOriginDeviceId(origin);
        recordRepository.save(record);

        ChangeLogEntity change = new ChangeLogEntity();
        change.setEntityType(normalizedType);
        change.setEntityId(entityId);
        change.setOperation(operation);
        change.setVersion(version);
        change.setOriginDeviceId(origin);
        change.setConflictId(conflictId);
        change.setPayloadJson(json);
        change.setChangedAt(Instant.now());
        change = changeRepository.saveAndFlush(change);
        record.setLastSequence(change.getSequence());
        recordRepository.save(record);
        return new PublishedChange(version, change.getSequence());
    }

    private SyncRecordEntity newRecord(String entityType, String entityId) {
        SyncRecordEntity record = new SyncRecordEntity();
        record.setId(UUID.randomUUID().toString());
        record.setEntityType(entityType);
        record.setEntityId(entityId);
        record.setVersion(0);
        return record;
    }

    private String write(JsonNode payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Payload JSON inválido.", exception);
        }
    }
}
