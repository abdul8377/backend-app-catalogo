package com.abdul.catalogo.product.importing.service;

import com.abdul.catalogo.masterdata.service.RelationalMasterDataService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class ProductMasterDataResolver {
    private final RelationalMasterDataService masters;

    public ProductMasterDataResolver(RelationalMasterDataService masters) {
        this.masters = masters;
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
        }

        String brandName = aggregate.path("brand").asText().trim();
        String brandId = aggregate.path("brandId").asText().trim();
        if (brandId.isBlank() && !companyId.isBlank()) {
            brandId = masters.findBrandId(companyId, brandName).orElse("");
            if (brandId.isBlank()) errors.add("No existe la marca " + brandName + " dentro de " + companyName + ".");
            else aggregate.put("brandId", brandId);
        }

        String categoryName = aggregate.path("category").asText().trim();
        String categoryId = aggregate.path("categoryId").asText().trim();
        if (categoryId.isBlank()) {
            categoryId = masters.findRootCategoryId(categoryName).orElse("");
            if (categoryId.isBlank()) errors.add("No existe la categoría principal " + categoryName + ".");
            else aggregate.put("categoryId", categoryId);
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
        }

        String effectiveCategoryId = subcategoryId.isBlank() ? categoryId : subcategoryId;
        if (!brandId.isBlank() && !effectiveCategoryId.isBlank()
                && !masters.brandAppliesToCategory(brandId, effectiveCategoryId)) {
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
        if (rawPricing instanceof ObjectNode pricing && pricing.path("price_lists") instanceof ArrayNode lists) {
            for (JsonNode raw : lists) {
                if (!(raw instanceof ObjectNode list)) continue;
                String name = list.path("name").asText("General").trim();
                String id = masters.findPriceListId(name).orElse("");
                if (!id.isBlank()) list.put("id", id);
                list.put("currency", "PEN");
            }
        }

        return new ProductImportCandidate(candidate.sourceRow(), candidate.familyCode(), candidate.productId(),
                candidate.expectedVersion(), aggregate, List.copyOf(warnings), List.copyOf(errors));
    }
}
