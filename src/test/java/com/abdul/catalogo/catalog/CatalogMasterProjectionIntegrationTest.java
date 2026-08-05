package com.abdul.catalogo.catalog;

import com.abdul.catalogo.catalog.service.CatalogMasterDataService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest
class CatalogMasterProjectionIntegrationTest {
    @Autowired CatalogMasterDataService masterDataService;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void projectsTheSameLogicalCatalogModelUsedBySQLite() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String companyId = "company-" + suffix;
        String brandId = "brand-" + suffix;
        String categoryId = "category-" + suffix;
        String subcategoryId = "subcategory-" + suffix;
        String relationId = "brand-category-" + suffix;
        String unitId = "unit-" + suffix;
        String attributeId = "attribute-" + suffix;
        String optionId = "option-" + suffix;
        String attributeUnitId = "attribute-unit-" + suffix;

        masterDataService.project("COMPANY", companyId, objectMapper.valueToTree(Map.of(
                "nombre", "Empresa " + suffix,
                "ruc", "206" + suffix,
                "estado", 1)), false);
        masterDataService.project("BRAND", brandId, objectMapper.valueToTree(Map.of(
                "nombre", "Marca " + suffix,
                "empresa_id", companyId,
                "estado", 1)), false);
        masterDataService.project("CATEGORY", categoryId, objectMapper.valueToTree(Map.of(
                "nombre", "Pernería " + suffix,
                "descripcion", "Categoría superior",
                "estado", 1)), false);
        masterDataService.project("CATEGORY", subcategoryId, objectMapper.valueToTree(Map.of(
                "nombre", "Pernos para moto " + suffix,
                "categoria_padre_id", categoryId,
                "estado", 1)), false);
        masterDataService.project("BRAND_CATEGORY", relationId, objectMapper.valueToTree(Map.of(
                "marca_id", brandId,
                "categoria_id", categoryId,
                "estado", 1)), false);
        masterDataService.project("MEASUREMENT_UNIT", unitId, objectMapper.valueToTree(Map.of(
                "codigo", "mm-" + suffix,
                "nombre", "Milímetro " + suffix,
                "simbolo", "mm",
                "magnitud", "Longitud",
                "factor_a_base", 1,
                "decimales", 3,
                "estado", 1)), false);
        masterDataService.project("CATEGORY_ATTRIBUTE", attributeId, objectMapper.valueToTree(Map.of(
                "categoria_id", subcategoryId,
                "nombre", "Diámetro",
                "clave", "diametro_" + suffix,
                "tipo_dato", "numero_unidad",
                "nivel_captura", "variante",
                "filtrable", 1,
                "puede_ser_eje", 1,
                "estado", 1)), false);
        masterDataService.project("CATEGORY_ATTRIBUTE_OPTION", optionId, objectMapper.valueToTree(Map.of(
                "categoria_atributo_id", attributeId,
                "etiqueta", "M6",
                "codigo", "m6-" + suffix,
                "estado", 1)), false);
        masterDataService.project("CATEGORY_ATTRIBUTE_UNIT", attributeUnitId, objectMapper.valueToTree(Map.of(
                "categoria_atributo_id", attributeId,
                "unidad_medida_id", unitId,
                "es_predeterminada", 1,
                "estado", 1)), false);

        assertThat(count("empresas", companyId)).isEqualTo(1);
        assertThat(count("marcas", brandId)).isEqualTo(1);
        assertThat(count("categorias", categoryId)).isEqualTo(1);
        assertThat(count("categorias", subcategoryId)).isEqualTo(1);
        assertThat(count("marca_categorias", relationId)).isEqualTo(1);
        assertThat(count("unidades_medida", unitId)).isEqualTo(1);
        assertThat(count("categoria_atributos", attributeId)).isEqualTo(1);
        assertThat(count("categoria_atributo_opciones", optionId)).isEqualTo(1);
        assertThat(count("categoria_atributo_unidades", attributeUnitId)).isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT categoria_padre_id FROM categorias WHERE id = ?", String.class, subcategoryId))
                .isEqualTo(categoryId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT empresa_id FROM marcas WHERE id = ?", String.class, brandId))
                .isEqualTo(companyId);

        ObjectNode product = objectMapper.createObjectNode();
        product.put("company", "Empresa " + suffix);
        product.put("companyId", "");
        product.put("brand", "Marca " + suffix);
        product.put("brandId", "");
        product.put("category", "Pernería " + suffix);
        product.put("categoryId", "");
        product.put("subcategory", "Pernos para moto " + suffix);
        product.put("subcategoryId", "");

        var classification = masterDataService.canonicalizeRequired(product);
        assertThat(classification.companyId()).isEqualTo(companyId);
        assertThat(classification.brandId()).isEqualTo(brandId);
        assertThat(classification.categoryId()).isEqualTo(categoryId);
        assertThat(classification.subcategoryId()).isEqualTo(subcategoryId);
        assertThat(product.path("companyId").asText()).isEqualTo(companyId);
        assertThat(product.path("brandId").asText()).isEqualTo(brandId);
        assertThat(product.path("categoryId").asText()).isEqualTo(categoryId);
        assertThat(product.path("subcategoryId").asText()).isEqualTo(subcategoryId);
    }

    private int count(String table, String id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE id = ?", Integer.class, id);
        return count == null ? 0 : count;
    }
}
