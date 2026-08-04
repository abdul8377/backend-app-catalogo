package com.abdul.catalogo.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.sync")
public record SyncProperties(
        int pushBatchSize,
        int pullBatchSize,
        int bootstrapBatchSize
) {
}
