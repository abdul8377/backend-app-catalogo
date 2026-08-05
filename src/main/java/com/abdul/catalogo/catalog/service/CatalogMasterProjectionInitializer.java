package com.abdul.catalogo.catalog.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(20)
public class CatalogMasterProjectionInitializer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(CatalogMasterProjectionInitializer.class);

    private final CatalogMasterDataService masterDataService;

    public CatalogMasterProjectionInitializer(CatalogMasterDataService masterDataService) {
        this.masterDataService = masterDataService;
    }

    @Override
    public void run(ApplicationArguments args) {
        int projected = masterDataService.backfillFromSyncRecords();
        if (projected > 0) {
            log.info("Se reconstruyeron {} proyecciones de datos maestros del catálogo.", projected);
        }
    }
}
