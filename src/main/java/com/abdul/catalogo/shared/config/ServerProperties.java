package com.abdul.catalogo.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.server")
public record ServerProperties(
        String name,
        boolean mdnsEnabled,
        String mdnsBindAddress,
        String serviceType,
        int publicPort
) {
}
