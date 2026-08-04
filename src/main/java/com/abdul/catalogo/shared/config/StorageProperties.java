package com.abdul.catalogo.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties("app.storage")
public record StorageProperties(Path root, long maxFileSizeBytes) {
}
