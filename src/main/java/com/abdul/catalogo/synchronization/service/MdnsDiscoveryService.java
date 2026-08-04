package com.abdul.catalogo.synchronization.service;

import com.abdul.catalogo.shared.config.ContractProperties;
import com.abdul.catalogo.shared.config.ServerProperties;
import jakarta.annotation.PreDestroy;
import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Map;

@Component
public class MdnsDiscoveryService {
    private static final Logger log = LoggerFactory.getLogger(MdnsDiscoveryService.class);

    private final ServerProperties properties;
    private final ContractProperties contractProperties;
    private final ServerIdentityService identityService;
    private JmDNS jmDNS;
    private ServiceInfo serviceInfo;

    public MdnsDiscoveryService(ServerProperties properties, ContractProperties contractProperties,
                                ServerIdentityService identityService) {
        this.properties = properties;
        this.contractProperties = contractProperties;
        this.identityService = identityService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.mdnsEnabled()) return;
        var identity = identityService.getOrCreate();
        try {
            jmDNS = JmDNS.create(InetAddress.getLocalHost());
            String instanceName = identity.getDisplayName() + "-" + identity.getServerId().substring(0, 8);
            serviceInfo = ServiceInfo.create(properties.serviceType(), instanceName, properties.publicPort(), 0, 0,
                    Map.of("serverId", identity.getServerId(), "name", identity.getDisplayName(),
                            "contract", contractProperties.version()));
            jmDNS.registerService(serviceInfo);
            log.info("Servidor anunciado por mDNS como {}", instanceName);
        } catch (IOException exception) {
            log.warn("No se pudo anunciar el servidor por mDNS; la URL manual sigue disponible: {}", exception.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        if (jmDNS == null) return;
        if (serviceInfo != null) jmDNS.unregisterService(serviceInfo);
        try {
            jmDNS.close();
        } catch (IOException exception) {
            log.debug("Error al cerrar mDNS", exception);
        }
    }
}
