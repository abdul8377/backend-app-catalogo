package com.abdul.catalogo.product.importing.repository;

import com.abdul.catalogo.product.importing.entity.ProductImportRowEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductImportRowRepository extends JpaRepository<ProductImportRowEntity, String> {
    List<ProductImportRowEntity> findByImportIdOrderByRowNumber(String importId);
    void deleteByImportId(String importId);
}
