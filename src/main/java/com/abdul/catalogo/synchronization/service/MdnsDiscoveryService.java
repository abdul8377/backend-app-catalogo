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
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
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
            BindCandidate bind = selectBindAddress();
            jmDNS = JmDNS.create(bind.address());
            String instanceName = identity.getDisplayName() + "-" + identity.getServerId().substring(0, 8);
            serviceInfo = ServiceInfo.create(properties.serviceType(), instanceName, properties.publicPort(), 0, 0,
                    Map.of("serverId", identity.getServerId(), "serverName", identity.getDisplayName(),
                            "apiContractVersion", contractProperties.version()));
            jmDNS.registerService(serviceInfo);
            log.info("Servidor anunciado por mDNS como {} en {} ({}) puerto {}",
                    instanceName, bind.address().getHostAddress(), bind.interfaceName(), properties.publicPort());
        } catch (IOException exception) {
            log.warn("No se pudo anunciar el servidor por mDNS; la URL manual sigue disponible: {}", exception.getMessage());
        }
    }

    BindCandidate selectBindAddress() throws IOException {
        String configured = properties.mdnsBindAddress();
        if (configured != null && !configured.isBlank()) {
            InetAddress address = InetAddress.getByName(configured.trim());
            if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) {
                throw new IOException("MDNS_BIND_ADDRESS debe ser una dirección IPv4 no loopback.");
            }
            NetworkInterface network = NetworkInterface.getByInetAddress(address);
            if (network == null || !network.isUp() || network.isLoopback() || !network.supportsMulticast()) {
                throw new IOException("MDNS_BIND_ADDRESS no pertenece a una interfaz activa con soporte multicast.");
            }
            return new BindCandidate(address, displayName(network), Integer.MAX_VALUE);
        }

        List<BindCandidate> candidates = new ArrayList<>();
        Enumeration<NetworkInterface> networks = NetworkInterface.getNetworkInterfaces();
        if (networks == null) throw new IOException("Windows no devolvió interfaces de red.");
        while (networks.hasMoreElements()) {
            NetworkInterface network = networks.nextElement();
            if (!usable(network)) continue;
            Enumeration<InetAddress> addresses = network.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                    candidates.add(new BindCandidate(address, displayName(network), score(network, address)));
                }
            }
        }
        return candidates.stream()
                .max(Comparator.comparingInt(BindCandidate::score)
                        .thenComparing(candidate -> candidate.address().getHostAddress()))
                .orElseThrow(() -> new IOException("No existe una interfaz IPv4 activa apta para mDNS."));
    }

    private boolean usable(NetworkInterface network) throws SocketException {
        return network.isUp() && !network.isLoopback() && !network.isPointToPoint() && network.supportsMulticast();
    }

    private int score(NetworkInterface network, InetAddress address) throws SocketException {
        String label = (network.getName() + " " + network.getDisplayName()).toLowerCase(Locale.ROOT);
        int score = address.isSiteLocalAddress() ? 100 : address.isLinkLocalAddress() ? 10 : 40;
        if (containsAny(label, "wi-fi", "wifi", "wireless", "wlan", "ethernet", " eth", " en")) score += 100;
        if (network.isVirtual() || containsAny(label, "virtual", "vpn", "hyper-v", "vmware", "vbox", "docker", "wsl", "tunnel")) {
            score -= 150;
        }
        return score;
    }

    private boolean containsAny(String value, String... fragments) {
        for (String fragment : fragments) if (value.contains(fragment)) return true;
        return false;
    }

    private String displayName(NetworkInterface network) {
        return network.getDisplayName() == null ? network.getName() : network.getDisplayName();
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

    record BindCandidate(InetAddress address, String interfaceName, int score) {
    }
}
