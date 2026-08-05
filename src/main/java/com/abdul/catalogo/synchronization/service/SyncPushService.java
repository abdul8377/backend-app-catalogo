package com.abdul.catalogo.synchronization.service;

import com.abdul.catalogo.shared.config.ContractProperties;
import com.abdul.catalogo.shared.config.SyncProperties;
import com.abdul.catalogo.shared.exception.BusinessRuleException;
import com.abdul.catalogo.synchronization.dto.SyncEventRequest;
import com.abdul.catalogo.synchronization.dto.SyncEventResult;
import com.abdul.catalogo.synchronization.dto.SyncPushRequest;
import com.abdul.catalogo.synchronization.dto.SyncPushResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

        List<SyncEventRequest> ordered = new ArrayList<>(request.events());
        ordered.sort(Comparator.comparingInt(event -> entityCatalog.dependencyRank(event.entityType())));
        Map<String, SyncEventResult> resultByEvent = new HashMap<>();
        for (SyncEventRequest event : ordered) {
            SyncEventResult result = lockService.withLock("EVENT", event.eventId(),
                    () -> lockService.withLock(event.entityType(), event.entityId(),
                            () -> eventProcessor.process(authenticatedDeviceId, event)));
            resultByEvent.put(event.eventId(), result);
        }

        List<SyncEventResult> results = request.events().stream()
                .map(event -> resultByEvent.get(event.eventId()))
                .toList();
        deviceService.markPushed(authenticatedDeviceId);
        return new SyncPushResponse(List.copyOf(results));
    }
}
