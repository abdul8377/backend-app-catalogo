package com.abdul.catalogo.product.web;

import com.abdul.catalogo.product.dto.ProductResponse;
import com.abdul.catalogo.product.model.ProductStatus;
import com.abdul.catalogo.product.model.ProductType;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public record ProductCardView(
        String id,
        String code,
        String name,
        String description,
        String company,
        String brand,
        String categoryPath,
        ProductType productType,
        String productTypeLabel,
        ProductStatus status,
        long version,
        String imageUrl,
        List<String> presentations,
        List<String> attributes,
        int attributeCount,
        int variantCount,
        String priceLabel,
        String priceState
) {

    public static ProductCardView from(ProductResponse product) {
        JsonNode aggregate = product.aggregate();
        List<String> presentations = presentations(aggregate.path("presentations"));
        AttributeSummary attributes = attributes(aggregate);
        int variants = aggregate.path("variants").isArray() ? aggregate.path("variants").size() : 0;
        PriceSummary price = price(aggregate.path("prices"), variants);
        String categoryPath = product.category();
        if (product.subcategory() != null && !product.subcategory().isBlank()) {
            categoryPath = categoryPath == null || categoryPath.isBlank()
                    ? product.subcategory()
                    : categoryPath + " › " + product.subcategory();
        }
        return new ProductCardView(
                product.id(),
                product.code(),
                product.name(),
                product.description(),
                product.company(),
                product.brand(),
                categoryPath == null ? "" : categoryPath,
                product.productType(),
                typeLabel(product.productType()),
                product.status(),
                product.version(),
                imageUrl(aggregate.path("images")),
                presentations,
                attributes.preview(),
                attributes.count(),
                variants,
                price.label(),
                price.state());
    }

    private static List<String> presentations(JsonNode rows) {
        if (!rows.isArray()) return List.of();
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode row : rows) {
            String name = row.path("name").asText(row.path("nombre").asText("")).trim();
            if (!name.isBlank()) values.add(name);
        }
        return values.stream().limit(4).toList();
    }

    private static AttributeSummary attributes(JsonNode aggregate) {
        List<String> preview = new ArrayList<>();
        JsonNode attributes = aggregate.path("attributes");
        int count = 0;
        if (attributes.isObject()) {
            count = attributes.size();
            for (String name : attributes.propertyNames()) {
                if (preview.size() >= 3) break;
                String value = readableValue(attributes.path(name));
                preview.add(value.isBlank() ? name : name + ": " + value);
            }
        }
        JsonNode normalized = aggregate.path("attributeValues");
        if (normalized.isArray()) count = Math.max(count, normalized.size());
        return new AttributeSummary(List.copyOf(preview), count);
    }

    private static String readableValue(JsonNode value) {
        if (value == null || value.isNull()) return "";
        if (value.isValueNode()) return value.asText("").trim();
        if (value.isArray()) {
            List<String> items = new ArrayList<>();
            for (JsonNode item : value) {
                String text = readableValue(item);
                if (!text.isBlank()) items.add(text);
                if (items.size() == 3) break;
            }
            return String.join(", ", items);
        }
        String text = value.path("value").asText(value.path("valor").asText("")).trim();
        String unit = value.path("unit").asText(value.path("unidad").asText("")).trim();
        return (text + (unit.isBlank() ? "" : " " + unit)).trim();
    }

    private static PriceSummary price(JsonNode rows, int variants) {
        if (!rows.isArray() || rows.isEmpty()) return new PriceSummary("Sin precio", "missing");
        double minimum = Double.POSITIVE_INFINITY;
        String currency = "PEN";
        boolean quoteRequired = false;
        for (JsonNode row : rows) {
            quoteRequired = quoteRequired || row.path("quoteRequired").asBoolean(false)
                    || row.path("requiere_cotizacion").asBoolean(false);
            String rowCurrency = row.path("currency").asText(row.path("moneda").asText("")).trim();
            if (!rowCurrency.isBlank()) currency = rowCurrency;
            JsonNode value = row.has("price") ? row.get("price") : row.get("valor");
            if (value != null && value.isNumber() && value.asDouble() > 0) {
                minimum = Math.min(minimum, value.asDouble());
            }
        }
        if (!Double.isFinite(minimum)) {
            return quoteRequired
                    ? new PriceSummary("Por cotizar", "quote")
                    : new PriceSummary("Sin precio", "missing");
        }
        String prefix = variants > 1 ? "Desde " : "";
        String symbol = switch (currency.toUpperCase(Locale.ROOT)) {
            case "USD" -> "US$";
            case "EUR" -> "€";
            default -> "S/";
        };
        return new PriceSummary(prefix + symbol + " " + String.format(Locale.US, "%.2f", minimum), "priced");
    }

    private static String imageUrl(JsonNode images) {
        if (!images.isArray() || images.isEmpty()) return null;
        JsonNode selected = null;
        for (JsonNode image : images) {
            if (selected == null) selected = image;
            if (image.path("primary").asBoolean(false) || image.path("principal").asBoolean(false)) {
                selected = image;
                break;
            }
        }
        if (selected == null) return null;
        String key = selected.path("storageKey").asText(selected.path("storage_key").asText("")).trim();
        String[] parts = key.replace('\\', '/').split("/");
        if (parts.length < 2 || parts[1].isBlank()) return null;
        return "/public/files/" + parts[1];
    }

    private static String typeLabel(ProductType type) {
        return switch (type) {
            case LIST -> "Con variantes";
            case MATRIX -> "Matriz de medidas";
            default -> "Producto único";
        };
    }

    private record AttributeSummary(List<String> preview, int count) {}
    private record PriceSummary(String label, String state) {}
}
