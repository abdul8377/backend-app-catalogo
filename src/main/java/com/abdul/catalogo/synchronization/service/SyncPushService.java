package com.abdul.catalogo.synchronization.service;

import com.abdul.catalogo.shared.config.ContractProperties;
import com.abdul.catalogo.shared.config.SyncProperties;
import com.abdul.catalogo.shared.exception.BusinessRuleException;
import com.abdul.catalogo.synchronization.dto.SyncEventRequest;
import com.abdul.catalogo.synchronization.dto.SyncEventResult;
import com.abdul.catalogo.synchronization.dto.SyncPushRequest;
import com.abdul.catalogo.synchronization.dto.SyncPushResponse;
import com.abdul.catalogo.synchronization.model.SyncOperation;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class SyncPushService {

    private final SyncProperties properties;
    private final SyncEventProcessor eventProcessor;
    private final DeviceService deviceService;
    private final ContractProperties contractProperties;
    private final EntityLockService lockService;
    private final SyncEntityCatalog entityCatalog;

    public SyncPushService(SyncProperties properties, SyncEventProcessor eventProcessor, DeviceService deviceService,
                           ContractProperties contractProperties, EntityLockService lockService,
                           SyncEntityCatalog entityCatalog) {
        this.properties = properties;
        this.eventProcessor = eventProcessor;
        this.deviceService = deviceService;
        this.contractProperties = contractProperties;
        this.lockService = lockService;
        this.entityCatalog = entityCatalog;
    }

    public SyncPushResponse push(String authenticatedDeviceId, SyncPushRequest request) {
        if (!authenticatedDeviceId.equals(request.deviceId())) {
            throw new BusinessRuleException("DEVICE_ID_MISMATCH", "El token no corresponde al deviceId del lote.");
        }
        if (!contractProperties.version().equals(request.apiContractVersion())) {
            throw new BusinessRuleException("INCOMPATIBLE_API_CONTRACT",
                    "El lote usa un contrato de sincronización incompatible.");
        }
        if (request.events().size() > properties.pushBatchSize()) {
            throw new BusinessRuleException("SYNC_BATCH_TOO_LARGE",
                    "El lote supera el máximo de " + properties.pushBatchSize() + " eventos.");
        }

        List<IndexedEvent> ordered = new ArrayList<>(request.events().size());
        for (int index = 0; index < request.events().size(); index++) {
            ordered.add(new IndexedEvent(index, request.events().get(index)));
        }
        ordered.sort(Comparator
                .comparingInt((IndexedEvent item) -> operationGroup(item.event()))
                .thenComparingInt(item -> dependencyRank(item.event()))
                .thenComparingInt(IndexedEvent::index));

        List<SyncEventResult> results = new ArrayList<>(java.util.Collections.nCopies(request.events().size(), null));
        for (IndexedEvent item : ordered) {
            SyncEventRequest event = item.event();
            SyncEventResult result = lockService.withLock("EVENT", event.eventId(),
                    () -> lockService.withLock(event.entityType(), event.entityId(),
                            () -> eventProcessor.process(authenticatedDeviceId, event)));
            results.set(item.index(), result);
        }
        deviceService.markPushed(authenticatedDeviceId);
        return new SyncPushResponse(List.copyOf(results));
    }

    private int operationGroup(SyncEventRequest event) {
        return event.operation() == SyncOperation.DELETE ? 1 : 0;
    }

    private int dependencyRank(SyncEventRequest event) {
        String normalized = event.entityType() == null
                ? ""
                : event.entityType().trim().toUpperCase(Locale.ROOT);
        int rank = entityCatalog.dependencyOrder().indexOf(normalized);
        if (rank < 0) rank = entityCatalog.dependencyOrder().size();
        return event.operation() == SyncOperation.DELETE ? -rank : rank;
    }

    private record IndexedEvent(int index, SyncEventRequest event) {
    }
}
