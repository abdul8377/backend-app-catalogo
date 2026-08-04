package com.abdul.catalogo.synchronization.service;

import com.abdul.catalogo.shared.config.ContractProperties;
import com.abdul.catalogo.shared.config.PairingProperties;
import com.abdul.catalogo.shared.config.ServerProperties;
import com.abdul.catalogo.shared.crypto.Digests;
import com.abdul.catalogo.shared.exception.BusinessRuleException;
import com.abdul.catalogo.synchronization.dto.PairingCodeResponse;
import com.abdul.catalogo.synchronization.entity.PairingCodeEntity;
import com.abdul.catalogo.synchronization.entity.ServerIdentityEntity;
import com.abdul.catalogo.synchronization.model.PairingCodeStatus;
import com.abdul.catalogo.synchronization.repository.PairingCodeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

@Service
public class PairingCodeService {
    private final PairingCodeRepository repository;
    private final PairingProperties properties;
    private final ServerProperties serverProperties;
    private final ContractProperties contractProperties;
    private final ServerIdentityService identityService;
    private final ObjectMapper objectMapper;
    private final QrCodeService qrCodeService;
    private final DeviceAuditService auditService;
    private final SecureRandom random = new SecureRandom();

    public PairingCodeService(PairingCodeRepository repository, PairingProperties properties,
                              ServerProperties serverProperties, ContractProperties contractProperties,
                              ServerIdentityService identityService, ObjectMapper objectMapper,
                              QrCodeService qrCodeService, DeviceAuditService auditService) {
        this.repository = repository;
        this.properties = properties;
        this.serverProperties = serverProperties;
        this.contractProperties = contractProperties;
        this.identityService = identityService;
        this.objectMapper = objectMapper;
        this.qrCodeService = qrCodeService;
        this.auditService = auditService;
    }

    @Transactional
    public PairingCodeResponse create(String actor) {
        String code = "%08d".formatted(random.nextInt(100_000_000));
        Instant now = Instant.now();
        PairingCodeEntity entity = new PairingCodeEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setCodeHash(Digests.sha256(code));
        entity.setStatus(PairingCodeStatus.PENDING);
        entity.setExpiresAt(now.plus(properties.codeDuration()));
        entity.setCreatedBy(actor);
        entity.setCreatedAt(now);
        repository.save(entity);
        auditService.record(null, "PAIRING_CODE_CREATED", actor, "pairingId=" + entity.getId() + ", expiresAt=" + entity.getExpiresAt());

        ServerIdentityEntity identity = identityService.getOrCreate();
        String qrPayload = qrPayload(identity, code);
        return new PairingCodeResponse(entity.getId(), code, entity.getExpiresAt(), identity.getServerId(),
                identity.getDisplayName(), serverProperties.serviceType(), contractProperties.version(), qrPayload,
                qrCodeService.asDataUrl(qrPayload));
    }

    @Transactional
    public PairingCodeEntity requireAvailable(String rawCode) {
        PairingCodeEntity code = repository.findByCodeHash(Digests.sha256(rawCode.trim()))
                .orElseThrow(() -> new BusinessRuleException("INVALID_PAIRING_CODE", "El código de emparejamiento no es válido."));
        if (code.getStatus() == PairingCodeStatus.USED) {
            throw new BusinessRuleException("PAIRING_CODE_ALREADY_USED", "El código de emparejamiento ya fue utilizado.");
        }
        if (code.getStatus() == PairingCodeStatus.EXPIRED || !code.getExpiresAt().isAfter(Instant.now())) {
            code.setStatus(PairingCodeStatus.EXPIRED);
            repository.save(code);
            throw new BusinessRuleException("PAIRING_CODE_EXPIRED", "El código de emparejamiento expiró.");
        }
        return code;
    }

    public void markUsed(PairingCodeEntity code, String deviceId) {
        code.setStatus(PairingCodeStatus.USED);
        code.setUsedAt(Instant.now());
        code.setUsedByDeviceId(deviceId);
        repository.save(code);
    }

    private String qrPayload(ServerIdentityEntity identity, String code) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("serverId", identity.getServerId());
        payload.put("serverName", identity.getDisplayName());
        payload.put("pairingCode", code);
        payload.put("serviceType", serverProperties.serviceType());
        payload.put("apiContractVersion", contractProperties.version());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException exception) {
            throw new IllegalStateException("No se pudo generar el contenido QR.", exception);
        }
    }
}
