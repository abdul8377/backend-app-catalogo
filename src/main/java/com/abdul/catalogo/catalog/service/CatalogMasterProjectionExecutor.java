package com.abdul.catalogo.catalog.service;

import com.abdul.catalogo.shared.exception.BusinessRuleException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
public class CatalogMasterProjectionExecutor {
    private final CatalogMasterDataService masterDataService;

    public CatalogMasterProjectionExecutor(CatalogMasterDataService masterDataService) {
        this.masterDataService = masterDataService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void project(String entityType, String entityId, JsonNode payload, boolean deleted) {
        try {
            masterDataService.project(entityType, entityId, payload, deleted);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessRuleException(
                    "DUPLICATE_MASTER_DATA",
                    "El dato maestro entra en conflicto con otro registro existente.");
        }
    }
}
