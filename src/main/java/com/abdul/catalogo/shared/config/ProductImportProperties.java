package com.abdul.catalogo.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("app.imports")
public record ProductImportProperties(
        long maxFileSizeBytes,
        int maxRows,
        Duration retention
) {
}
