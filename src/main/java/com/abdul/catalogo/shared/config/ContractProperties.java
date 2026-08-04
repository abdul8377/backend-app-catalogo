package com.abdul.catalogo.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.contract")
public record ContractProperties(String version, int payloadVersion, String schemaVersion) {
}
