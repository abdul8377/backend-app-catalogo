package com.abdul.catalogo.shared.ops;

import com.abdul.catalogo.product.importing.model.ProductImportStatus;
import com.abdul.catalogo.product.importing.repository.ProductImportRepository;
import com.abdul.catalogo.product.importing.repository.ProductImportRowRepository;
import com.abdul.catalogo.shared.config.ProductImportProperties;
import com.abdul.catalogo.storage.StorageService;
import com.abdul.catalogo.synchronization.model.PairingCodeStatus;
import com.abdul.catalogo.synchronization.repository.PairingCodeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

@Service
public class RetentionService {
    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);
    private final PairingCodeRepository pairingRepository;
    private final ProductImportRepository importRepository;
    private final ProductImportRowRepository rowRepository;
    private final ProductImportProperties properties;
    private final StorageService storageService;
    public RetentionService(PairingCodeRepository pairingRepository, ProductImportRepository importRepository,
                            ProductImportRowRepository rowRepository, ProductImportProperties properties,
                            StorageService storageService) {
        this.pairingRepository = pairingRepository; this.importRepository = importRepository;
        this.rowRepository = rowRepository; this.properties = properties; this.storageService = storageService;
    }

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void clean() {
        Instant now = Instant.now();
        pairingRepository.findByStatusAndExpiresAtBefore(PairingCodeStatus.PENDING, now)
                .forEach(code -> code.setStatus(PairingCodeStatus.EXPIRED));
        var expiredImports = importRepository.findByCreatedAtBeforeAndStatusIn(now.minus(properties.retention()),
                List.of(ProductImportStatus.CONFIRMED, ProductImportStatus.FAILED));
        for (var item : expiredImports) {
            try { storageService.delete(item.getStorageKey()); }
            catch (IOException exception) { log.warn("No se pudo limpiar {}: {}", item.getStorageKey(), exception.getMessage()); continue; }
            rowRepository.deleteByImportId(item.getId()); importRepository.delete(item);
        }
        log.info("Retención: {} importaciones temporales eliminadas", expiredImports.size());
    }
}
