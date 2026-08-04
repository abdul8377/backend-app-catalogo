package com.abdul.catalogo.synchronization.service;

import com.abdul.catalogo.shared.config.ContractProperties;
import com.abdul.catalogo.shared.config.ServerProperties;
import com.abdul.catalogo.synchronization.dto.DiscoveryResponse;
import com.abdul.catalogo.synchronization.entity.ServerIdentityEntity;
import com.abdul.catalogo.synchronization.repository.PairingCodeRepository;
import com.abdul.catalogo.synchronization.repository.ServerIdentityRepository;
import com.abdul.catalogo.synchronization.model.PairingCodeStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class ServerIdentityService {
    private static final short SINGLETON_ID = 1;

    private final ServerIdentityRepository repository;
    private final PairingCodeRepository pairingCodeRepository;
    private final ServerProperties serverProperties;
    private final ContractProperties contractProperties;

    public ServerIdentityService(ServerIdentityRepository repository, PairingCodeRepository pairingCodeRepository,
                                 ServerProperties serverProperties, ContractProperties contractProperties) {
        this.repository = repository;
        this.pairingCodeRepository = pairingCodeRepository;
        this.serverProperties = serverProperties;
        this.contractProperties = contractProperties;
    }

    @Transactional
    public ServerIdentityEntity getOrCreate() {
        return repository.findById(SINGLETON_ID).orElseGet(() -> {
            Instant now = Instant.now();
            ServerIdentityEntity identity = new ServerIdentityEntity();
            identity.setSingletonId(SINGLETON_ID);
            identity.setServerId(UUID.randomUUID().toString());
            identity.setDisplayName(serverProperties.name().trim());
            identity.setCreatedAt(now);
            identity.setUpdatedAt(now);
            return repository.saveAndFlush(identity);
        });
    }

    @Transactional
    public DiscoveryResponse discovery() {
        ServerIdentityEntity identity = repository.findById(SINGLETON_ID).orElse(null);
        if (identity == null) {
            identity = getOrCreate();
        }
        boolean available = pairingCodeRepository.existsByStatusAndExpiresAtAfter(PairingCodeStatus.PENDING, Instant.now());
        return new DiscoveryResponse(identity.getServerId(), identity.getDisplayName(), serverProperties.serviceType(),
                serverProperties.publicPort(), contractProperties.version(), available);
    }
}
