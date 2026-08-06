package com.abdul.catalogo.synchronization.service;

import com.abdul.catalogo.shared.exception.BusinessRuleException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class SyncEntityCatalog {
    private static final List<String> DEPENDENCY_ORDER = List.of(
            "COMPANY", "BRAND", "CATEGORY", "BRAND_CATEGORY", "MEASUREMENT_UNIT",
            "CATEGORY_ATTRIBUTE", "CATEGORY_ATTRIBUTE_OPTION", "CATEGORY_ATTRIBUTE_UNIT",
            "LEGACY_ATTRIBUTE_DEFINITION", "PRICE_LIST", "PRODUCT", "CLIENT", "ORDER_SHEET", "ORDER",
            "ORDER_ITEM", "QUOTE", "QUOTE_ITEM", "PREPARATION", "PREPARATION_STOCK_MOVEMENT",
            "ORDER_LOAD", "ORDER_HISTORY", "ORDER_SHEET_HISTORY");
    private static final Set<String> SUPPORTED = Set.copyOf(DEPENDENCY_ORDER);
    private static final Set<String> APPEND_ONLY = Set.of(
            "PREPARATION_STOCK_MOVEMENT", "ORDER_HISTORY", "ORDER_SHEET_HISTORY");

    public String normalizeAndValidate(String entityType) {
        String normalized = entityType == null ? "" : entityType.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED.contains(normalized)) {
            throw new BusinessRuleException("UNSUPPORTED_ENTITY_TYPE",
                    "La entidad " + normalized + " no está habilitada en el contrato de sincronización.");
        }
        return normalized;
    }

    public boolean isAppendOnly(String entityType) {
        return APPEND_ONLY.contains(entityType);
    }

    public List<String> dependencyOrder() {
        return DEPENDENCY_ORDER;
    }
}
