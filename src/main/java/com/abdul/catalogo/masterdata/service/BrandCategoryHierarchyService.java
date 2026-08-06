package com.abdul.catalogo.masterdata.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

@Service
public class BrandCategoryHierarchyService {
    private final JdbcTemplate jdbc;

    public BrandCategoryHierarchyService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean brandAppliesToCategory(String brandId, String categoryId) {
        if (brandId == null || brandId.isBlank() || categoryId == null || categoryId.isBlank()) {
            return false;
        }
        Set<String> visited = new HashSet<>();
        String currentCategoryId = categoryId;
        while (currentCategoryId != null && visited.add(currentCategoryId)) {
            Integer matches = jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM marca_categorias
                    WHERE marca_id = ?
                      AND categoria_id = ?
                      AND estado = TRUE
                      AND deleted = FALSE
                    """, Integer.class, brandId, currentCategoryId);
            if (matches != null && matches > 0) return true;
            currentCategoryId = jdbc.query("""
                    SELECT categoria_padre_id
                    FROM categorias
                    WHERE id = ? AND deleted = FALSE
                    """, resultSet -> resultSet.next() ? resultSet.getString(1) : null, currentCategoryId);
        }
        return false;
    }
}
