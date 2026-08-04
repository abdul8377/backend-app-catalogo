package com.abdul.catalogo.storage;

import com.abdul.catalogo.storage.model.StoredFileStatus;
import com.abdul.catalogo.storage.repository.StoredFileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

@Service
public class StoredFileRetentionService {
    private static final Logger log = LoggerFactory.getLogger(StoredFileRetentionService.class);
    private final StoredFileRepository repository;
    private final StorageService storageService;

    public StoredFileRetentionService(StoredFileRepository repository, StorageService storageService) {
        this.repository = repository;
        this.storageService = storageService;
    }

    @Scheduled(cron = "0 */15 * * * *")
    @Transactional
    public void expireIncompleteUploads() {
        var expired = repository.findByStatusInAndExpiresAtBefore(
                List.of(StoredFileStatus.INTENT, StoredFileStatus.UPLOADED), Instant.now());
        int completed = 0;
        for (var file : expired) {
            try {
                storageService.delete(file.getStorageKey());
            } catch (IOException exception) {
                log.warn("No se pudo limpiar el upload incompleto {}: {}", file.getId(), exception.getMessage());
                continue;
            }
            file.setStatus(StoredFileStatus.EXPIRED);
            completed++;
        }
        if (completed > 0) log.info("Uploads incompletos expirados: {}", completed);
    }
}
