package com.abdul.catalogo.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

@ConfigurationProperties("app.storage")
public record StorageProperties(Path root, long maxFileSizeBytes, Duration intentDuration) {
}
