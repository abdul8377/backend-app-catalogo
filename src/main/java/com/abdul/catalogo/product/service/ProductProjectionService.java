package com.abdul.catalogo.product.service;

import com.abdul.catalogo.product.entity.ProductEntity;
import com.abdul.catalogo.product.model.ProductStatus;
import com.abdul.catalogo.product.model.ProductType;
import com.abdul.catalogo.product.repository.ProductRepository;
import com.abdul.catalogo.shared.exception.BusinessRuleException;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class ProductProjectionService {
    private static final Set<String> REQUIRED_PRODUCT_FIELDS = Set.of(
            "productId", "code", "name", "description", "company", "companyId", "brand", "brandId",
            "category", "categoryId", "subcategory", "subcategoryId", "productType", "status",
            "attributes", "variants", "presentations", "prices", "images");

    private static final Set<String> SUPPORTED_PRODUCT_FIELDS = Set.of(
            "productId", "code", "name", "description", "company", "companyId", "brand", "brandId",
            "category", "categoryId", "subcategory", "subcategoryId", "productType", "status",
            "attributes", "variants", "presentations", "prices", "images",
            "familyAxes", "attributeValues", "attributeOptions",
            "salesConfiguration", "pricingConfiguration", "imageConfiguration");

    private final ProductRepository productRepository;
    private final ProductRelationalProjectionService relationalProjectionService;
    private final ObjectMapper objectMapper;

    public ProductProjectionService(ProductRepository productRepository,
                                    ProductRelationalProjectionService relationalProjectionService,
                                    ObjectMapper objectMapper) {
        this.productRepository = productRepository;
        this.relationalProjectionService = relationalProjectionService;
        this.objectMapper = objectMapper;
    }

    public void validateUpsert(String productId, JsonNode payload) {
        JsonNode aggregate = aggregate(payload);
        if (aggregate == null || !aggregate.isObject()) {
            throw new BusinessRuleException("INVALID_PRODUCT_AGGREGATE", "PRODUCT debe ser un objeto JSON completo.");
        }
        Set<String> fields = Set.copyOf(aggregate.propertyNames());
        if (!fields.containsAll(REQUIRED_PRODUCT_FIELDS)) {
            Set<String> missing = new HashSet<>(REQUIRED_PRODUCT_FIELDS);
            missing.removeAll(fields);
            throw new BusinessRuleException("INVALID_PRODUCT_FIELDS",
                    "PRODUCT no contiene los bloques requeridos: " + String.join(", ", missing) + ".");
        }
        if (!SUPPORTED_PRODUCT_FIELDS.containsAll(fields)) {
            Set<String> unsupported = new HashSet<>(fields);
            unsupported.removeAll(SUPPORTED_PRODUCT_FIELDS);
            throw new BusinessRuleException("INVALID_PRODUCT_FIELDS",
                    "PRODUCT contiene campos no soportados: " + String.join(", ", unsupported) + ".");
        }
        if (!productId.equals(aggregate.path("productId").asText())) {
            throw new BusinessRuleException("PRODUCT_ID_MISMATCH", "productId debe coincidir con entityId.");
        }
        requireStringFields(aggregate);
        validateIdentityAndClassification(aggregate);
        validateUniqueCode(productId, text(aggregate, "code"));
        validateAggregateCollections(aggregate);
    }

    public void apply(String productId, JsonNode payload, long version, String originDeviceId, boolean deleted) {
        ProductEntity product = productRepository.findById(productId).orElseGet(ProductEntity::new);
        product.setId(productId);
        product.setVersion(version);
        product.setOriginDeviceId(originDeviceId);
        if (deleted) {
            product.setStatus(ProductStatus.DELETED);
            product.setDeletedAt(Instant.now());
            if (product.getCode() == null) initializeDeleted(product, productId);
            productRepository.saveAndFlush(product);
            relationalProjectionService.clear(productId);
            return;
        }

        JsonNode aggregate = aggregate(payload);
        product.setCode(text(aggregate, "code"));
        product.setName(text(aggregate, "name"));
        product.setDescription(text(aggregate, "description"));
        product.setCompany(text(aggregate, "company"));
        product.setCompanyId(text(aggregate, "companyId"));
        product.setBrand(text(aggregate, "brand"));
        product.setBrandId(text(aggregate, "brandId"));
        product.setCategory(text(aggregate, "category"));
        product.setCategoryId(text(aggregate, "categoryId"));
        product.setSubcategory(text(aggregate, "subcategory"));
        product.setSubcategoryId(text(aggregate, "subcategoryId"));
        product.setProductType(productType(aggregate));
        product.setStatus(status(aggregate));
        product.setDeletedAt(null);
        product.setAggregateJson(write(aggregate));
        productRepository.saveAndFlush(product);
        relationalProjectionService.replace(productId, aggregate);
    }

    private void validateIdentityAndClassification(JsonNode aggregate) {
        if (text(aggregate, "code").isBlank()) {
            throw new BusinessRuleException("PRODUCT_CODE_REQUIRED", "Ingresa el código interno de la familia.");
        }
        if (text(aggregate, "name").isBlank()) {
            throw new BusinessRuleException("PRODUCT_NAME_REQUIRED", "Ingresa el nombre comercial del producto.");
        }
        if (text(aggregate, "company").isBlank() || text(aggregate, "brand").isBlank()
                || text(aggregate, "category").isBlank()) {
            throw new BusinessRuleException("PRODUCT_CLASSIFICATION_REQUIRED",
                    "Completa empresa, marca y categoría antes de continuar.");
        }
        productType(aggregate);
        status(aggregate);
    }

    private void validateUniqueCode(String productId, String code) {
        boolean duplicate = productRepository.findById(productId).isPresent()
                ? productRepository.existsByCodeIgnoreCaseAndIdNot(code, productId)
                : productRepository.existsByCodeIgnoreCase(code);
        if (duplicate) {
            throw new BusinessRuleException("DUPLICATE_PRODUCT_CODE", "Ya existe otro producto con el código " + code + ".");
        }
    }

    private void validateAggregateCollections(JsonNode aggregate) {
        JsonNode attributes = aggregate.get("attributes");
        if (attributes == null || !attributes.isObject()) {
            throw new BusinessRuleException("INVALID_PRODUCT_ATTRIBUTES", "Los atributos comunes deben formar un objeto.");
        }
        requireArray(aggregate, "variants");
        requireArray(aggregate, "presentations");
        requireArray(aggregate, "prices");
        requireArray(aggregate, "images");
        requireOptionalArray(aggregate, "familyAxes");
        requireOptionalArray(aggregate, "attributeValues");
        requireOptionalArray(aggregate, "attributeOptions");
        requireOptionalObject(aggregate, "salesConfiguration");
        requireOptionalObject(aggregate, "pricingConfiguration");
        requireOptionalObject(aggregate, "imageConfiguration");

        VariantSummary variantSummary = validateVariants(aggregate.path("variants"), productType(aggregate));
        PresentationSummary presentationSummary = validatePresentations(aggregate.path("presentations"), variantSummary.activeSkus());
        validatePrices(aggregate.path("prices"), variantSummary.allSkus(), presentationSummary);
        validateImages(aggregate.path("images"));

        if (status(aggregate) == ProductStatus.ACTIVE) {
            validateActiveProduct(aggregate, variantSummary, presentationSummary);
        }
    }

    private VariantSummary validateVariants(JsonNode variants, ProductType type) {
        if (variants.isEmpty()) {
            throw new BusinessRuleException("PRODUCT_VARIANTS_REQUIRED", "Registra al menos una variante vendible.");
        }
        if (type == ProductType.SINGLE && variants.size() != 1) {
            throw new BusinessRuleException("SINGLE_PRODUCT_VARIANT_COUNT",
                    "Un producto único debe tener exactamente una variante automática.");
        }

        Set<String> all = new HashSet<>();
        Set<String> active = new HashSet<>();
        for (JsonNode variant : variants) {
            String sku = text(variant, "sku").toUpperCase(Locale.ROOT);
            String shortName = firstText(variant, "shortName", "nombre_corto");
            if (sku.isBlank()) {
                throw new BusinessRuleException("PRODUCT_VARIANT_SKU_REQUIRED", "Todas las variantes necesitan un SKU.");
            }
            if (!all.add(sku)) {
                throw new BusinessRuleException("DUPLICATE_VARIANT_SKU", "El SKU " + sku + " está repetido.");
            }
            if (shortName.isBlank()) {
                throw new BusinessRuleException("PRODUCT_VARIANT_NAME_REQUIRED",
                        "La variante " + sku + " necesita un nombre corto.");
            }
            String variantStatus = firstText(variant, "status", "estado").toUpperCase(Locale.ROOT);
            if (variantStatus.isBlank() || variantStatus.equals("ACTIVE") || variantStatus.equals("1") || variantStatus.equals("TRUE")) {
                active.add(sku);
            }
            JsonNode variantAttributes = variant.get("attributes");
            if (variantAttributes != null && !variantAttributes.isObject()) {
                throw new BusinessRuleException("INVALID_VARIANT_ATTRIBUTES",
                        "Los atributos de la variante " + sku + " deben formar un objeto.");
            }
        }
        if (active.isEmpty()) {
            throw new BusinessRuleException("ACTIVE_VARIANT_REQUIRED", "Activa al menos una variante para continuar.");
        }
        return new VariantSummary(Set.copyOf(all), Set.copyOf(active));
    }

    private PresentationSummary validatePresentations(JsonNode presentations, Set<String> activeSkus) {
        if (presentations.isEmpty()) {
            throw new BusinessRuleException("PRODUCT_PRESENTATIONS_REQUIRED",
                    "Agrega al menos una presentación vendible.");
        }
        Set<String> keys = new HashSet<>();
        Set<String> familyNames = new HashSet<>();
        Map<String, Set<String>> namesBySku = new HashMap<>();
        for (JsonNode presentation : presentations) {
            String name = firstText(presentation, "name", "nombre");
            String sku = text(presentation, "sku").toUpperCase(Locale.ROOT);
            if (name.isBlank()) {
                throw new BusinessRuleException("PRESENTATION_NAME_REQUIRED", "Cada presentación necesita un nombre.");
            }
            if (!sku.isBlank() && !activeSkus.contains(sku)) {
                throw new BusinessRuleException("INVALID_PRODUCT_REFERENCE",
                        "La presentación " + name + " referencia el SKU inexistente o inactivo " + sku + ".");
            }
            BigDecimal equivalence = decimal(presentation, "equivalence", "equivalencia");
            BigDecimal minimum = decimal(presentation, "minimumSale", "minimumOrder", "venta_minima");
            BigDecimal increment = decimal(presentation, "purchaseIncrement", "increment", "incremento");
            if (equivalence == null || equivalence.signum() <= 0) {
                throw new BusinessRuleException("INVALID_PRESENTATION_EQUIVALENCE",
                        "La equivalencia de " + name + " debe ser mayor que cero.");
            }
            if (minimum != null && minimum.signum() <= 0) {
                throw new BusinessRuleException("INVALID_PRESENTATION_MINIMUM",
                        "La venta mínima de " + name + " debe ser mayor que cero.");
            }
            if (increment != null && increment.signum() <= 0) {
                throw new BusinessRuleException("INVALID_PRESENTATION_INCREMENT",
                        "El incremento de " + name + " debe ser mayor que cero.");
            }
            String normalizedName = normalize(name);
            String key = sku + "::" + normalizedName;
            if (!keys.add(key)) {
                throw new BusinessRuleException("DUPLICATE_PRESENTATION",
                        "La presentación " + name + " está repetida para el mismo SKU.");
            }
            if (sku.isBlank()) familyNames.add(normalizedName);
            else namesBySku.computeIfAbsent(sku, ignored -> new HashSet<>()).add(normalizedName);
        }

        for (String sku : activeSkus) {
            boolean covered = !familyNames.isEmpty() || !namesBySku.getOrDefault(sku, Set.of()).isEmpty();
            if (!covered) {
                throw new BusinessRuleException("VARIANT_WITHOUT_PRESENTATION",
                        "La variante " + sku + " no tiene una presentación vendible.");
            }
        }
        return new PresentationSummary(Set.copyOf(familyNames), immutableNested(namesBySku));
    }

    private void validatePrices(JsonNode prices, Set<String> allSkus, PresentationSummary presentations) {
        Set<String> keys = new HashSet<>();
        for (JsonNode price : prices) {
            String sku = text(price, "sku").toUpperCase(Locale.ROOT);
            if (!sku.isBlank() && !allSkus.contains(sku)) {
                throw new BusinessRuleException("INVALID_PRODUCT_REFERENCE",
                        "Un precio referencia el SKU inexistente " + sku + ".");
            }
            String presentation = firstText(price, "presentation", "presentacion");
            if (presentation.isBlank()) {
                throw new BusinessRuleException("PRICE_PRESENTATION_REQUIRED", "Cada precio debe indicar una presentación.");
            }
            if (!presentations.appliesTo(sku, presentation)) {
                throw new BusinessRuleException("PRICE_PRESENTATION_NOT_FOUND",
                        "El precio referencia una presentación que no corresponde al SKU " + sku + ".");
            }
            String list = firstText(price, "priceList", "lista_precio_nombre");
            if (list.isBlank()) list = "General";
            String key = normalize(list) + "::" + sku + "::" + normalize(presentation);
            if (!keys.add(key)) {
                throw new BusinessRuleException("DUPLICATE_PRODUCT_PRICE",
                        "Existe más de un precio para la misma lista, variante y presentación.");
            }
            BigDecimal value = decimal(price, "price", "valor", "fixedPrice");
            if (value != null && value.signum() < 0) {
                throw new BusinessRuleException("NEGATIVE_PRODUCT_PRICE", "El precio no puede ser negativo.");
            }
        }
    }

    private void validateImages(JsonNode images) {
        int primary = 0;
        for (JsonNode image : images) {
            String storageKey = firstText(image, "storageKey", "storage_key").replace('\\', '/');
            if (storageKey.isBlank() || storageKey.startsWith("/") || storageKey.contains(":")
                    || storageKey.equals("..") || storageKey.contains("../") || !storageKey.startsWith("files/")) {
                throw new BusinessRuleException("INVALID_PRODUCT_IMAGE_KEY",
                        "Cada imagen debe usar una referencia generada por el backend.");
            }
            if (booleanValue(image, "primary", "principal", "isPrimary")) primary++;
        }
        if (primary > 1) {
            throw new BusinessRuleException("MULTIPLE_PRIMARY_IMAGES", "Solo una imagen puede ser la principal.");
        }
    }

    private void validateActiveProduct(JsonNode aggregate, VariantSummary variants, PresentationSummary presentations) {
        JsonNode images = aggregate.path("images");
        if (images.isEmpty() || !hasPrimaryImage(images)) {
            throw new BusinessRuleException("ACTIVE_PRODUCT_IMAGE_REQUIRED",
                    "Para activar el producto agrega una imagen principal lista.");
        }
        JsonNode prices = aggregate.path("prices");
        for (String sku : variants.activeSkus()) {
            for (String presentation : presentations.namesFor(sku)) {
                if (!hasReadyPrice(prices, sku, presentation)) {
                    throw new BusinessRuleException("ACTIVE_PRODUCT_PRICE_REQUIRED",
                            "Falta configurar el precio o marcar por cotizar: " + sku + " · " + presentation + ".");
                }
            }
        }
    }

    private boolean hasReadyPrice(JsonNode prices, String sku, String presentation) {
        for (JsonNode price : prices) {
            String rowSku = text(price, "sku").toUpperCase(Locale.ROOT);
            String rowPresentation = firstText(price, "presentation", "presentacion");
            if ((!rowSku.isBlank() && !rowSku.equals(sku)) || !normalize(rowPresentation).equals(normalize(presentation))) {
                continue;
            }
            if (booleanValue(price, "quoteRequired", "requiere_cotizacion")) return true;
            String configuration = firstText(price, "configuration", "configuracion").toLowerCase(Locale.ROOT);
            if (configuration.equals("quote") || configuration.equals("por_cotizar")) return true;
            BigDecimal value = decimal(price, "price", "valor", "fixedPrice");
            if (value != null && value.signum() >= 0) return true;
        }
        return false;
    }

    private boolean hasPrimaryImage(JsonNode images) {
        for (JsonNode image : images) if (booleanValue(image, "primary", "principal", "isPrimary")) return true;
        return false;
    }

    private Map<String, Set<String>> immutableNested(Map<String, Set<String>> source) {
        Map<String, Set<String>> result = new HashMap<>();
        source.forEach((key, value) -> result.put(key, Set.copyOf(value)));
        return Map.copyOf(result);
    }

    private void requireArray(JsonNode aggregate, String field) {
        JsonNode value = aggregate.get(field);
        if (value == null || !value.isArray()) {
            throw new BusinessRuleException("INVALID_PRODUCT_AGGREGATE", field + " debe ser una lista.");
        }
    }

    private void requireOptionalArray(JsonNode aggregate, String field) {
        JsonNode value = aggregate.get(field);
        if (value != null && !value.isArray()) {
            throw new BusinessRuleException("INVALID_PRODUCT_AGGREGATE", field + " debe ser una lista.");
        }
    }

    private void requireOptionalObject(JsonNode aggregate, String field) {
        JsonNode value = aggregate.get(field);
        if (value != null && !value.isObject()) {
            throw new BusinessRuleException("INVALID_PRODUCT_AGGREGATE", field + " debe ser un objeto.");
        }
    }

    private void initializeDeleted(ProductEntity product, String productId) {
        product.setCode("deleted-" + productId);
        product.setName("Producto eliminado");
        product.setDescription("");
        product.setCompany("");
        product.setCompanyId("");
        product.setBrand("");
        product.setBrandId("");
        product.setCategory("");
        product.setCategoryId("");
        product.setSubcategory("");
        product.setSubcategoryId("");
        product.setProductType(ProductType.SINGLE);
        product.setAggregateJson("{}");
    }

    private JsonNode aggregate(JsonNode payload) {
        return payload;
    }

    private ProductType productType(JsonNode node) {
        try {
            return ProductType.valueOf(node.path("productType").asText("SINGLE").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BusinessRuleException("INVALID_PRODUCT_TYPE", "Tipo de producto no reconocido.");
        }
    }

    private ProductStatus status(JsonNode node) {
        String value = text(node, "status");
        try {
            return ProductStatus.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BusinessRuleException("INVALID_PRODUCT_STATUS", "Estado de producto no reconocido.");
        }
    }

    private void requireStringFields(JsonNode node) {
        for (String field : Set.of("productId", "code", "name", "description", "company", "companyId", "brand",
                "brandId", "category", "categoryId", "subcategory", "subcategoryId", "productType", "status")) {
            if (!node.path(field).isString()) {
                throw new BusinessRuleException("INVALID_PRODUCT_FIELD_TYPE", field + " debe ser texto.");
            }
        }
    }

    private String text(JsonNode node, String primary) {
        if (node == null) return "";
        JsonNode value = node.get(primary);
        return value == null || value.isNull() ? "" : value.asText().trim();
    }

    private String firstText(JsonNode node, String... names) {
        for (String name : names) {
            String value = text(node, name);
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private BigDecimal decimal(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value == null || value.isNull() || value.asText("").isBlank()) continue;
            try {
                return value.isNumber() ? value.decimalValue() : new BigDecimal(value.asText().replace(',', '.'));
            } catch (NumberFormatException exception) {
                throw new BusinessRuleException("INVALID_NUMERIC_VALUE", name + " debe ser numérico.");
            }
        }
        return null;
    }

    private boolean booleanValue(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value == null || value.isNull()) continue;
            if (value.isBoolean()) return value.asBoolean();
            String raw = value.asText().trim();
            if (raw.equalsIgnoreCase("true") || raw.equals("1") || raw.equalsIgnoreCase("si") || raw.equalsIgnoreCase("sí")) return true;
        }
        return false;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String write(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JacksonException exception) {
            throw new BusinessRuleException("INVALID_JSON", "No se pudo guardar el agregado del producto.");
        }
    }

    private record VariantSummary(Set<String> allSkus, Set<String> activeSkus) {}

    private record PresentationSummary(Set<String> familyNames, Map<String, Set<String>> namesBySku) {
        boolean appliesTo(String sku, String name) {
            String normalized = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
            if (sku == null || sku.isBlank()) {
                return familyNames.contains(normalized) || namesBySku.values().stream().anyMatch(values -> values.contains(normalized));
            }
            return familyNames.contains(normalized) || namesBySku.getOrDefault(sku.toUpperCase(Locale.ROOT), Set.of()).contains(normalized);
        }

        Set<String> namesFor(String sku) {
            Set<String> result = new HashSet<>(familyNames);
            result.addAll(namesBySku.getOrDefault(sku.toUpperCase(Locale.ROOT), Set.of()));
            return Set.copyOf(result);
        }
    }
}
