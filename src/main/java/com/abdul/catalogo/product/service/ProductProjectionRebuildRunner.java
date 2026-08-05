package com.abdul.catalogo.product.service;

import com.abdul.catalogo.product.model.ProductStatus;
import com.abdul.catalogo.product.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class ProductProjectionRebuildRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProductProjectionRebuildRunner.class);

    private final ProductRepository productRepository;
    private final ProductRelationalProjectionService projectionService;
    private final ObjectMapper objectMapper;

    public ProductProjectionRebuildRunner(ProductRepository productRepository,
                                          ProductRelationalProjectionService projectionService,
                                          ObjectMapper objectMapper) {
        this.productRepository = productRepository;
        this.projectionService = projectionService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        int rebuilt = 0;
        int failed = 0;
        for (var product : productRepository.findAll()) {
            try {
                if (product.getStatus() == ProductStatus.DELETED) {
                    projectionService.clear(product.getId());
                    continue;
                }
                JsonNode aggregate = objectMapper.readTree(product.getAggregateJson());
                if (aggregate != null && aggregate.isObject()) {
                    projectionService.replace(product.getId(), aggregate);
                    rebuilt++;
                }
            } catch (Exception exception) {
                failed++;
                log.warn("No se pudo reconstruir la proyección relacional del producto {}: {}",
                        product.getId(), exception.getMessage());
            }
        }
        if (rebuilt > 0 || failed > 0) {
            log.info("Proyección relacional de productos: {} reconstruidos, {} con error", rebuilt, failed);
        }
    }
}
