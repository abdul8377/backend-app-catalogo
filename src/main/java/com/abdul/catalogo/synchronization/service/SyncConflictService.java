package com.abdul.catalogo.synchronization.service;

import com.abdul.catalogo.product.service.ProductProjectionService;
import com.abdul.catalogo.shared.exception.BusinessRuleException;
import com.abdul.catalogo.shared.exception.ResourceNotFoundException;
import com.abdul.catalogo.synchronization.dto.SyncConflictResponse;
import com.abdul.catalogo.synchronization.entity.SyncConflictEntity;
import com.abdul.catalogo.synchronization.model.ConflictResolution;
import com.abdul.catalogo.synchronization.model.ConflictStatus;
import com.abdul.catalogo.synchronization.model.SyncOperation;
import com.abdul.catalogo.synchronization.repository.SyncConflictRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class SyncConflictService {
    private final SyncConflictRepository repository;
    private final ServerChangePublisher publisher;
    private final ProductProjectionService productProjectionService;
    private final ObjectMapper objectMapper;

    public SyncConflictService(SyncConflictRepository repository, ServerChangePublisher publisher,
                               ProductProjectionService productProjectionService, ObjectMapper objectMapper) {
        this.repository = repository; this.publisher = publisher;
        this.productProjectionService = productProjectionService; this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<SyncConflictResponse> pending() {
        return repository.findByStatusOrderByCreatedAtDesc(ConflictStatus.PENDING).stream().map(this::response).toList();
    }

    @Transactional
    public SyncConflictResponse resolve(String id, ConflictResolution resolution, String mergePayload, String actor) {
        SyncConflictEntity conflict = repository.findForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("CONFLICT_NOT_FOUND", "El conflicto no existe."));
        if (conflict.getStatus() != ConflictStatus.PENDING) return response(conflict);
        JsonNode selected = null;
        if (resolution == ConflictResolution.ACCEPT_TABLET) selected = read(conflict.getClientPayload());
        if (resolution == ConflictResolution.MERGE) {
            selected = read(mergePayload);
            if (!selected.isObject()) throw new BusinessRuleException("INVALID_MERGE_PAYLOAD", "El payload combinado debe ser un objeto JSON.");
        }
        if (selected != null) {
            var published = publisher.publish(conflict.getEntityType(), conflict.getEntityId(), SyncOperation.UPSERT,
                    selected, "server-conflict", conflict.getServerVersion());
            if (conflict.getEntityType().equals("PRODUCT")) {
                productProjectionService.validateUpsert(conflict.getEntityId(), selected);
                productProjectionService.apply(conflict.getEntityId(), selected, published.version(), "server-conflict", false);
            }
            conflict.setResolutionPayload(write(selected));
            conflict.setResolutionEventId(UUID.randomUUID().toString());
        }
        conflict.setResolution(resolution.name()); conflict.setResolvedBy(actor); conflict.setResolvedAt(Instant.now());
        conflict.setStatus(switch (resolution) {
            case KEEP_SERVER -> ConflictStatus.RESOLVED_SERVER;
            case ACCEPT_TABLET -> ConflictStatus.RESOLVED_TABLET;
            case MERGE -> ConflictStatus.RESOLVED_MERGED;
        });
        return response(conflict);
    }

    private SyncConflictResponse response(SyncConflictEntity conflict) {
        return new SyncConflictResponse(conflict.getId(), conflict.getEntityType(), conflict.getEntityId(),
                conflict.getServerVersion(), conflict.getClientBaseVersion(), read(conflict.getServerPayload()),
                read(conflict.getClientPayload()), conflict.getOriginDeviceId(), conflict.getStatus(), conflict.getCreatedAt(),
                conflict.getResolvedAt(), conflict.getResolvedBy(), conflict.getResolution());
    }
    private JsonNode read(String json) { try { return objectMapper.readTree(json == null || json.isBlank() ? "{}" : json); } catch (JacksonException e) { throw new BusinessRuleException("INVALID_JSON", "El conflicto contiene JSON inválido."); } }
    private String write(JsonNode node) { try { return objectMapper.writeValueAsString(node); } catch (JacksonException e) { throw new IllegalStateException(e); } }
}
