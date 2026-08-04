package com.abdul.catalogo.synchronization.service;

import com.abdul.catalogo.shared.exception.BusinessRuleException;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

@Component
public class SyncEntityCatalog {

    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "COMPANY",
            "BRAND",
            "CATEGORY",
            "BRAND_CATEGORY",
            "MEASUREMENT_UNIT",
            "CATEGORY_ATTRIBUTE",
            "CATEGORY_ATTRIBUTE_OPTION",
            "CATEGORY_ATTRIBUTE_UNIT",
            "LEGACY_ATTRIBUTE_DEFINITION",
            "PRODUCT",
            "PRODUCT_VARIANT",
            "PRODUCT_FAMILY_AXIS",
            "PRODUCT_ATTRIBUTE",
            "PRODUCT_ATTRIBUTE_OPTION",
            "CLIENT",
            "ORDER_SHEET",
            "ORDER",
            "ORDER_ITEM",
            "QUOTE",
            "QUOTE_ITEM",
            "PREPARATION",
            "PREPARATION_STOCK_MOVEMENT",
            "ORDER_LOAD",
            "ORDER_HISTORY",
            "ORDER_SHEET_HISTORY"
    );

    public String normalizeAndValidate(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_TYPES.contains(normalized)) {
            throw new BusinessRuleException("UNSUPPORTED_ENTITY_TYPE", "El tipo de entidad no está habilitado: " + normalized);
        }
        return normalized;
    }

    public Set<String> supportedTypes() {
        return SUPPORTED_TYPES;
    }
}
