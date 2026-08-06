package com.abdul.catalogo.product.importing.service;

import com.abdul.catalogo.masterdata.service.BrandCategoryHierarchyService;
import com.abdul.catalogo.masterdata.service.RelationalMasterDataService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class ProductMasterDataResolver {
    private final RelationalMasterDataService masters;
    private final BrandCategoryHierarchyService hierarchy;
    private final JdbcTemplate jdbc;

    public ProductMasterDataResolver(RelationalMasterDataService masters,
                                     BrandCategoryHierarchyService hierarchy,
                                     JdbcTemplate jdbc) {
        this.masters = masters;
        this.hierarchy = hierarchy;
        this.jdbc = jdbc;
    }

    public ProductImportCandidate resolve(ProductImportCandidate candidate) {
        ObjectNode aggregate = candidate.aggregate().deepCopy();
        List<String> warnings = new ArrayList<>(candidate.warnings());
        List<String> errors = new ArrayList<>(candidate.errors());

        String companyName = aggregate.path("company").asText().trim();
        String companyId = aggregate.path("companyId").asText().trim();
        if (companyId.isBlank()) {
            companyId = masters.findCompanyId(companyName).orElse("");
            if (companyId.isBlank()) errors.add("No existe la empresa " + companyName + " en los datos maestros.");
            else aggregate.put("companyId", companyId);
        } else if (!matchesCompany(companyId, companyName)) {
            errors.add("EmpresaId no existe o no corresponde a " + companyName + ".");
        }

        String brandName = aggregate.path("brand").asText().trim();
        String brandId = aggregate.path("brandId").asText().trim();
        if (brandId.isBlank() && !companyId.isBlank()) {
            brandId = masters.findBrandId(companyId, brandName).orElse("");
            if (brandId.isBlank()) errors.add("No existe la marca " + brandName + " dentro de " + companyName + ".");
            else aggregate.put("brandId", brandId);
        } else if (!brandId.isBlank() && !matchesBrand(brandId, companyId, brandName)) {
            errors.add("MarcaId no existe, no corresponde a " + brandName + " o pertenece a otra empresa.");
        }

        String categoryName = aggregate.path("category").asText().trim();
        String categoryId = aggregate.path("categoryId").asText().trim();
        if (categoryId.isBlank()) {
            categoryId = masters.findRootCategoryId(categoryName).orElse("");
            if (categoryId.isBlank()) errors.add("No existe la categoría principal " + categoryName + ".");
            else aggregate.put("categoryId", categoryId);
        } else if (!matchesCategory(categoryId, null, categoryName)) {
            errors.add("CategoriaId no existe o no corresponde a la categoría principal " + categoryName + ".");
        }

        String subcategoryName = aggregate.path("subcategory").asText().trim();
        String subcategoryId = aggregate.path("subcategoryId").asText().trim();
        if (!subcategoryName.isBlank() && subcategoryId.isBlank() && !categoryId.isBlank()) {
            subcategoryId = masters.findChildCategoryId(categoryId, subcategoryName).orElse("");
            if (subcategoryId.isBlank()) {
                errors.add("No existe la subcategoría " + subcategoryName + " dentro de " + categoryName + ".");
            } else {
                aggregate.put("subcategoryId", subcategoryId);
            }
        } else if (!subcategoryId.isBlank() && !matchesCategory(subcategoryId, categoryId, subcategoryName)) {
            errors.add("SubcategoriaId no existe o no pertenece a " + categoryName + ".");
        }

        String effectiveCategoryId = subcategoryId.isBlank() ? categoryId : subcategoryId;
        if (!brandId.isBlank() && !effectiveCategoryId.isBlank()
                && !hierarchy.brandAppliesToCategory(brandId, effectiveCategoryId)) {
            errors.add("La marca " + brandName + " no está relacionada con "
                    + (subcategoryName.isBlank() ? categoryName : categoryName + " > " + subcategoryName)
                    + " ni con una categoría antecesora.");
        }

        JsonNode rawPrices = aggregate.path("prices");
        if (rawPrices instanceof ArrayNode prices) {
            for (int index = 0; index < prices.size(); index++) {
                if (!(prices.get(index) instanceof ObjectNode price)) continue;
                String currency = price.path("currency").asText("PEN").trim().toUpperCase(Locale.ROOT);
                if (!currency.equals("PEN")) {
                    errors.add("Precios: la moneda de todas las filas debe ser PEN.");
                }
                price.put("currency", "PEN");
                String listName = price.path("priceList").asText("General").trim();
                String listId = masters.findPriceListId(listName).orElse("");
                if (listId.isBlank()) {
                    errors.add("No existe la lista de precios " + listName + ". Cárgala primero en Datos maestros.");
                } else {
                    price.put("priceListId", listId);
                }
            }
        }
        JsonNode rawPricing = aggregate.path("pricingConfiguration");
        if (rawPricing instanceof ObjectNode pricing && pricing.path("lists") instanceof ArrayNode lists) {
            Map<String, String> resolvedListIds = new LinkedHashMap<>();
            for (JsonNode raw : lists) {
                if (!(raw instanceof ObjectNode list)) continue;
                String previousId = list.path("id").asText("").trim();
                String name = list.path("name").asText("General").trim();
                String id = masters.findPriceListId(name).orElse("");
                if (id.isBlank()) {
                    errors.add("No existe la lista de precios " + name + ".");
                } else {
                    list.put("id", id);
                    if (!previousId.isBlank()) resolvedListIds.put(previousId, id);
                }
                list.put("currency_code", "PEN");
                list.put("currency", "PEN");
            }
            JsonNode rawConfiguredPrices = pricing.path("prices");
            if (rawConfiguredPrices instanceof ArrayNode configuredPrices) {
                for (JsonNode raw : configuredPrices) {
                    if (!(raw instanceof ObjectNode configuredPrice)) continue;
                    String previousListId = configuredPrice.path("list_id").asText("").trim();
                    String resolvedListId = resolvedListIds.get(previousListId);
                    if (resolvedListId != null) configuredPrice.put("list_id", resolvedListId);
                }
            }
        }

        return new ProductImportCandidate(candidate.sourceRow(), candidate.familyCode(), candidate.productId(),
                candidate.expectedVersion(), aggregate, List.copyOf(warnings), List.copyOf(errors));
    }

    private boolean matchesCompany(String id, String name) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM empresas
                WHERE id = ? AND nombre_normalizado = ? AND deleted = FALSE
                """, Integer.class, id, masters.normalize(name));
        return count != null && count == 1;
    }

    private boolean matchesBrand(String id, String companyId, String name) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM marcas
                WHERE id = ? AND empresa_id = ? AND nombre_normalizado = ? AND deleted = FALSE
                """, Integer.class, id, companyId, masters.normalize(name));
        return count != null && count == 1;
    }

    private boolean matchesCategory(String id, String parentId, String name) {
        Integer count;
        if (parentId == null || parentId.isBlank()) {
            count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM categorias
                    WHERE id = ? AND categoria_padre_id IS NULL
                      AND nombre_normalizado = ? AND deleted = FALSE
                    """, Integer.class, id, masters.normalize(name));
        } else {
            count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM categorias
                    WHERE id = ? AND categoria_padre_id = ?
                      AND nombre_normalizado = ? AND deleted = FALSE
                    """, Integer.class, id, parentId, masters.normalize(name));
        }
        return count != null && count == 1;
    }
}
