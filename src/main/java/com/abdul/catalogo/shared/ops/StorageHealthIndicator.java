package com.abdul.catalogo.shared.ops;

import com.abdul.catalogo.shared.config.StorageProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class StorageHealthIndicator implements HealthIndicator {
    private final StorageProperties properties;
    public StorageHealthIndicator(StorageProperties properties) { this.properties = properties; }
    @Override public Health health() {
        Path root = properties.root().toAbsolutePath().normalize();
        return Files.isDirectory(root) && Files.isWritable(root)
                ? Health.up().withDetail("root", root.toString()).build()
                : Health.down().withDetail("root", root.toString()).withDetail("reason", "Directorio ausente o sin escritura").build();
    }
}
