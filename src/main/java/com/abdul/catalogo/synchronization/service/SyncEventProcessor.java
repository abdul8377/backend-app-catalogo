package com.abdul.catalogo.synchronization.service;

import com.abdul.catalogo.masterdata.service.MasterDataPayloadValidator;
import com.abdul.catalogo.masterdata.service.RelationalMasterDataService;
import com.abdul.catalogo.product.service.ProductProjectionService;
import com.abdul.catalogo.shared.config.ContractProperties;
import com.abdul.catalogo.shared.crypto.Digests;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

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
    private final RelationalMasterDataService masterDataService;
    private final MasterDataPayloadValidator masterPayloadValidator;
    private final ObjectMapper objectMapper;
    private final ContractProperties contractProperties;

    public SyncEventProcessor(SyncEntityCatalog entityCatalog, SyncRecordRepository recordRepository,
                              ProcessedEventRepository eventRepository, ChangeLogRepository changeRepository,
                              SyncConflictRepository conflictRepository, ProductProjectionService productProjectionService,
                              RelationalMasterDataService masterDataService,
                              MasterDataPayloadValidator masterPayloadValidator,
                              ObjectMapper objectMapper, ContractProperties contractProperties) {
        this.entityCatalog = entityCatalog;
        this.recordRepository = recordRepository;
        this.eventRepository = eventRepository;
        this.changeRepository = changeRepository;
        this.conflictRepository = conflictRepository;
        this.productProjectionService = productProjectionService;
        this.masterDataService = masterDataService;
        this.masterPayloadValidator = masterPayloadValidator;
        this.objectMapper = objectMapper;
        this.contractProperties = contractProperties;
    }

    @Transactional
    public SyncEventResult process(String deviceId, SyncEventRequest event) {
        String payloadJson = write(event.payload());
        String requestChecksum = requestChecksum(event, payloadJson);
        ProcessedEventEntity previous = eventRepository.findById(event.eventId()).orElse(null);
        if (previous != null) {
            if (!previous.getDeviceId().equals(deviceId)) {
                return new SyncEventResult(event.eventId(), SyncResultStatus.REJECTED, previous.getServerVersion(),
                        previous.getServerSequence(), null, "El eventId pertenece a otro dispositivo.");
            }
            if (previous.getRequestChecksum() != null && !previous.getRequestChecksum().equals(requestChecksum)) {
                return new SyncEventResult(event.eventId(), SyncResultStatus.REJECTED, previous.getServerVersion(),
                        previous.getServerSequence(), previous.getConflictId(),
                        "El eventId ya fue usado con un contenido diferente.");
            }
            if (previous.getStatus() == SyncResultStatus.REJECTED) {
                eventRepository.delete(previous);
                eventRepository.flush();
            } else {
                return new SyncEventResult(event.eventId(), SyncResultStatus.ALREADY_PROCESSED,
                        previous.getServerVersion(), previous.getServerSequence(), previous.getConflictId(), previous.getMessage());
            }
        }

        final String entityType;
        try {
            if (event.payloadVersion() != contractProperties.payloadVersion()) {
                throw new BusinessRuleException("INCOMPATIBLE_PAYLOAD_VERSION",
                        "El evento usa payloadVersion=" + event.payloadVersion()
                                + " y el servidor requiere " + contractProperties.payloadVersion() + ".");
            }
            if (!contractProperties.schemaVersion().equals(event.schemaVersion())) {
                throw new BusinessRuleException("INCOMPATIBLE_SCHEMA_VERSION",
                        "El evento usa schemaVersion=" + event.schemaVersion()
                                + " y el servidor requiere " + contractProperties.schemaVersion() + ".");
            }
            entityType = entityCatalog.normalizeAndValidate(event.entityType());
            if (entityCatalog.isAppendOnly(entityType) && event.operation() == SyncOperation.DELETE) {
                throw new BusinessRuleException("APPEND_ONLY_DELETE", "Los historiales y movimientos son append-only.");
            }
            if (event.checksum() != null && !event.checksum().equalsIgnoreCase(Digests.sha256(payloadJson))) {
                throw new BusinessRuleException("PAYLOAD_CHECKSUM_MISMATCH", "El checksum declarado no corresponde al payload.");
            }
            if (masterDataService.supports(entityType)) {
                masterPayloadValidator.validate(entityType, event.payload(), event.operation() == SyncOperation.DELETE);
            }
            if (entityType.equals("PRODUCT") && event.operation() == SyncOperation.UPSERT) {
                validateProductBackup(event.entityId(), event.payload());
            }
        } catch (BusinessRuleException exception) {
            saveProcessed(event, deviceId, requestChecksum, SyncResultStatus.REJECTED,
                    null, null, null, exception.getMessage());
            return new SyncEventResult(event.eventId(), SyncResultStatus.REJECTED,
                    null, null, null, exception.getMessage());
        }

        SyncRecordEntity record = recordRepository.findForUpdate(entityType, event.entityId()).orElse(null);
        long currentVersion = record == null ? 0 : record.getVersion();
        if (event.baseVersion() != currentVersion) {
            String conflictId = createConflict(deviceId, event, entityType, payloadJson, record, currentVersion);
            String message = "La versión del servidor cambió desde la última sincronización.";
            saveProcessed(event, deviceId, requestChecksum, SyncResultStatus.CONFLICT,
                    currentVersion, null, conflictId, message);
            return new SyncEventResult(event.eventId(), SyncResultStatus.CONFLICT,
                    currentVersion, null, conflictId, message);
        }

        if (record == null) {
            record = new SyncRecordEntity();
            record.setId(UUID.randomUUID().toString());
            record.setEntityType(entityType);
            record.setEntityId(event.entityId());
        }
        long nextVersion = currentVersion + 1;
        boolean deleted = event.operation() == SyncOperation.DELETE;
        record.setPayloadJson(masterDataService.supports(entityType) ? "{}" : payloadJson);
        record.setVersion(nextVersion);
        record.setDeleted(deleted);
        record.setDeletedAt(deleted ? Instant.now() : null);
        record.setOriginDeviceId(deviceId);
        recordRepository.saveAndFlush(record);

        if (masterDataService.supports(entityType)) {
            masterDataService.apply(entityType, event.entityId(), event.payload(), nextVersion, deviceId, deleted);
        } else if (entityType.equals("PRODUCT")) {
            productProjectionService.apply(event.entityId(), event.payload(), nextVersion, deviceId, deleted);
        }

        ChangeLogEntity change = new ChangeLogEntity();
        change.setEntityType(entityType);
        change.setEntityId(event.entityId());
        change.setOperation(event.operation());
        change.setVersion(nextVersion);
        change.setOriginDeviceId(deviceId);
        change.setConflictId(null);
        change.setPayloadJson(payloadJson);
        change.setChangedAt(Instant.now());
        change = changeRepository.saveAndFlush(change);
        record.setLastSequence(change.getSequence());
        recordRepository.save(record);
        masterDataService.updateLastSequence(entityType, event.entityId(), change.getSequence());

        saveProcessed(event, deviceId, requestChecksum, SyncResultStatus.ACCEPTED,
                nextVersion, change.getSequence(), null, null);
        return new SyncEventResult(event.eventId(), SyncResultStatus.ACCEPTED,
                nextVersion, change.getSequence(), null, null);
    }

    private void validateProductBackup(String productId, JsonNode payload) {
        if (!(payload instanceof ObjectNode product)) {
            productProjectionService.validateUpsert(productId, payload);
            return;
        }
        ObjectNode validationCopy = product.deepCopy();
        validationCopy.put("status", "DRAFT");
        productProjectionService.validateUpsert(productId, validationCopy);
    }

    private String createConflict(String deviceId, SyncEventRequest event, String entityType,
                                  String clientPayload, SyncRecordEntity record, long currentVersion) {
        SyncConflictEntity conflict = new SyncConflictEntity();
        conflict.setId(UUID.randomUUID().toString());
        conflict.setEntityType(entityType);
        conflict.setEntityId(event.entityId());
        conflict.setServerVersion(currentVersion);
        conflict.setClientBaseVersion(event.baseVersion());
        conflict.setServerPayload(masterDataService.supports(entityType)
                ? masterDataService.currentPayload(entityType, event.entityId()).orElse("{}")
                : record == null ? "{}" : record.getPayloadJson());
        conflict.setClientPayload(clientPayload);
        conflict.setOriginDeviceId(deviceId);
        conflict.setStatus(ConflictStatus.PENDING);
        conflict.setCreatedAt(Instant.now());
        conflictRepository.save(conflict);
        return conflict.getId();
    }

    private void saveProcessed(SyncEventRequest event, String deviceId, String requestChecksum,
                               SyncResultStatus status, Long serverVersion, Long serverSequence,
                               String conflictId, String message) {
        ProcessedEventEntity processed = new ProcessedEventEntity();
        processed.setEventId(event.eventId());
        processed.setDeviceId(deviceId);
        processed.setStatus(status);
        processed.setServerVersion(serverVersion);
        processed.setServerSequence(serverSequence);
        processed.setConflictId(conflictId);
        processed.setProcessedAt(Instant.now());
        processed.setMessage(message);
        processed.setRequestChecksum(requestChecksum);
        processed.setPayloadVersion(event.payloadVersion());
        processed.setSchemaVersion(event.schemaVersion());
        eventRepository.save(processed);
    }

    private String requestChecksum(SyncEventRequest event, String payloadJson) {
        String canonical = String.join("\n", event.entityType().trim().toUpperCase(), event.entityId(),
                event.operation().name(), Long.toString(event.baseVersion()), Integer.toString(event.payloadVersion()),
                event.schemaVersion() == null ? "" : event.schemaVersion(), event.occurredAt().toString(), payloadJson);
        return Digests.sha256(canonical);
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new BusinessRuleException("INVALID_JSON", "El payload no es JSON válido.");
        }
    }
}
