package com.abdul.catalogo.synchronization.service;

import com.abdul.catalogo.shared.config.SyncProperties;
import com.abdul.catalogo.shared.exception.BusinessRuleException;
import com.abdul.catalogo.synchronization.dto.BootstrapRecordDto;
import com.abdul.catalogo.synchronization.dto.SyncBootstrapResponse;
import com.abdul.catalogo.synchronization.dto.SyncChangeDto;
import com.abdul.catalogo.synchronization.dto.SyncPullResponse;
import com.abdul.catalogo.synchronization.dto.SyncPullAckResponse;
import com.abdul.catalogo.synchronization.dto.SyncStatusResponse;
import com.abdul.catalogo.synchronization.entity.ChangeLogEntity;
import com.abdul.catalogo.synchronization.model.ConflictStatus;
import com.abdul.catalogo.synchronization.repository.ChangeLogRepository;
import com.abdul.catalogo.synchronization.repository.ProcessedEventRepository;
import com.abdul.catalogo.synchronization.repository.SyncConflictRepository;
import com.abdul.catalogo.synchronization.repository.SyncRecordRepository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class SyncReadService {

    private final SyncProperties properties;
    private final ChangeLogRepository changeRepository;
    private final SyncRecordRepository recordRepository;
    private final ProcessedEventRepository eventRepository;
    private final SyncConflictRepository conflictRepository;
    private final SyncEntityCatalog entityCatalog;
    private final DeviceService deviceService;
    private final ObjectMapper objectMapper;
    private final ServerIdentityService identityService;

    public SyncReadService(SyncProperties properties, ChangeLogRepository changeRepository,
                           SyncRecordRepository recordRepository, ProcessedEventRepository eventRepository,
                           SyncConflictRepository conflictRepository, SyncEntityCatalog entityCatalog,
                           DeviceService deviceService, ObjectMapper objectMapper,
                           ServerIdentityService identityService) {
        this.properties = properties;
        this.changeRepository = changeRepository;
        this.recordRepository = recordRepository;
        this.eventRepository = eventRepository;
        this.conflictRepository = conflictRepository;
        this.entityCatalog = entityCatalog;
        this.deviceService = deviceService;
        this.objectMapper = objectMapper;
        this.identityService = identityService;
    }

    @Transactional
    public SyncPullResponse pull(String deviceId, long after, int requestedLimit) {
        int limit = validateLimit(requestedLimit, properties.pullBatchSize());
        long latestCursor = latestCursor();
        if (after < 0 || after > latestCursor) {
            throw new BusinessRuleException("PULL_CURSOR_AHEAD", "El cursor solicitado no existe en el servidor.");
        }
        List<ChangeLogEntity> fetched = changeRepository.findBySequenceGreaterThanOrderBySequenceAsc(
                Math.max(0, after), PageRequest.of(0, limit + 1));
        boolean hasMore = fetched.size() > limit;
        List<ChangeLogEntity> page = hasMore ? fetched.subList(0, limit) : fetched;
        List<SyncChangeDto> changes = page.stream().map(this::toChange).toList();
        long nextCursor = page.isEmpty() ? Math.max(0, after) : page.get(page.size() - 1).getSequence();
        deviceService.markDelivered(deviceId, nextCursor);
        return new SyncPullResponse(nextCursor, hasMore, changes);
    }

    @Transactional
    public SyncPullAckResponse acknowledge(String deviceId, long cursor) {
        return new SyncPullAckResponse(deviceService.acknowledgePull(deviceId, cursor), Instant.now());
    }

    @Transactional(readOnly = true)
    public SyncBootstrapResponse bootstrap(int requestedPage, int requestedLimit, Long requestedSnapshotCursor) {
        int pageNumber = Math.max(0, requestedPage);
        int limit = validateLimit(requestedLimit, properties.bootstrapBatchSize());
        long latestCursor = latestCursor();
        if (pageNumber > 0 && requestedSnapshotCursor == null) {
            throw new BusinessRuleException("BOOTSTRAP_SNAPSHOT_REQUIRED", "Las páginas siguientes deben conservar snapshotCursor.");
        }
        if (requestedSnapshotCursor != null && (requestedSnapshotCursor < 0 || requestedSnapshotCursor > latestCursor)) {
            throw new BusinessRuleException("INVALID_BOOTSTRAP_SNAPSHOT", "snapshotCursor no existe en el servidor.");
        }
        var page = recordRepository.findBootstrapPage(PageRequest.of(pageNumber, limit));
        List<BootstrapRecordDto> records = page.getContent().stream()
                .map(record -> new BootstrapRecordDto(record.getEntityType(), record.getEntityId(), record.getVersion(),
                        record.isDeleted(), read(record.getPayloadJson()), record.getUpdatedAt()))
                .toList();
        long snapshotCursor = requestedSnapshotCursor == null ? latestCursor : requestedSnapshotCursor;
        return new SyncBootstrapResponse(pageNumber, pageNumber + 1, page.hasNext(), snapshotCursor, records);
    }

    @Transactional(readOnly = true)
    public SyncStatusResponse status() {
        var discovery = identityService.discovery();
        return new SyncStatusResponse(discovery.serverId(), discovery.apiContractVersion(),
                recordRepository.count(), changeRepository.count(), eventRepository.count(),
                conflictRepository.countByStatus(ConflictStatus.PENDING), entityCatalog.supportedTypes(), Instant.now());
    }

    private long latestCursor() {
        ChangeLogEntity latest = changeRepository.findTopByOrderBySequenceDesc();
        return latest == null ? 0 : latest.getSequence();
    }

    private SyncChangeDto toChange(ChangeLogEntity change) {
        return new SyncChangeDto(change.getSequence(), change.getEntityType(), change.getEntityId(),
                change.getOperation(), change.getVersion(), change.getOriginDeviceId(),
                read(change.getPayloadJson()), change.getChangedAt());
    }

    private JsonNode read(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JacksonException exception) {
            throw new IllegalStateException("La base contiene un payload JSON inválido.", exception);
        }
    }

    private int validateLimit(int requested, int maximum) {
        if (requested < 1 || requested > maximum) {
            throw new BusinessRuleException("INVALID_SYNC_LIMIT", "El límite debe estar entre 1 y " + maximum + ".");
        }
        return requested;
    }
}
