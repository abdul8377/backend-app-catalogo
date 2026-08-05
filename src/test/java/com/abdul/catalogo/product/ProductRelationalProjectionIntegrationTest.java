package com.abdul.catalogo.product;

import com.abdul.catalogo.product.repository.ProductRepository;
import com.abdul.catalogo.product.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest
class ProductRelationalProjectionIntegrationTest {

    @Autowired ProductService productService;
    @Autowired ProductRepository productRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;

    @Test
    void sqliteAggregateIsStoredInProductsAndRelationalProjectionTables() throws Exception {
        ObjectNode aggregate = (ObjectNode) objectMapper.readTree(
                Files.readString(Path.of("docs", "contracts", "examples", "product-aggregate.json")));
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String productId = UUID.randomUUID().toString();
        String variantId = UUID.randomUUID().toString();
        String attributeId = UUID.randomUUID().toString();

        aggregate.put("productId", productId);
        aggregate.put("code", "SYNC-" + suffix);
        ((ObjectNode) aggregate.path("variants").get(0)).put("id", variantId).put("sku", "SKU-" + suffix);
        ((ObjectNode) aggregate.path("presentations").get(0)).put("sku", "SKU-" + suffix);
        ((ObjectNode) aggregate.path("prices").get(0)).put("sku", "SKU-" + suffix);
        ((ObjectNode) aggregate.path("attributeValues").get(0))
                .put("id", attributeId)
                .put("producto_id", productId);
        ((ObjectNode) aggregate.path("attributeOptions").get(0))
                .put("producto_atributo_id", attributeId);
        ((ObjectNode) aggregate.path("familyAxes").get(0)).put("producto_id", productId);
        ((ObjectNode) aggregate.path("images").get(0))
                .put("storageKey", "files/" + UUID.randomUUID() + "/content");

        productService.createAggregate(aggregate, "tablet-integration-test");

        assertThat(productRepository.findById(productId)).isPresent();
        assertCount("producto_variantes_catalogo", productId, 1);
        assertCount("producto_familia_ejes", productId, 1);
        assertCount("producto_atributos", productId, 1);
        assertCount("producto_atributo_opciones", productId, 1);
        assertCount("producto_presentaciones", productId, 1);
        assertCount("producto_precios", productId, 1);
        assertCount("producto_imagenes", productId, 1);

        String storedSku = jdbcTemplate.queryForObject(
                "SELECT sku FROM producto_variantes_catalogo WHERE producto_id = ?",
                String.class,
                productId);
        assertThat(storedSku).isEqualTo("SKU-" + suffix);

        Double price = jdbcTemplate.queryForObject(
                "SELECT precio FROM producto_precios WHERE producto_id = ?",
                Double.class,
                productId);
        assertThat(price).isEqualTo(10d);
    }

    private void assertCount(String table, String productId, int expected) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE producto_id = ?",
                Integer.class,
                productId);
        assertThat(count).isEqualTo(expected);
    }
}
