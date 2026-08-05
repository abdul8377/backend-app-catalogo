package com.abdul.catalogo.product.repository;

import com.abdul.catalogo.product.entity.ProductEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ProductRepository extends JpaRepository<ProductEntity, String> {
    boolean existsByCodeIgnoreCase(String code);
    boolean existsByCodeIgnoreCaseAndIdNot(String code, String id);
    Optional<ProductEntity> findByCodeIgnoreCase(String code);

    @Query("""
            select product from ProductEntity product
            where lower(product.name) like lower(concat('%', :query, '%'))
               or lower(product.code) like lower(concat('%', :query, '%'))
               or lower(product.company) like lower(concat('%', :query, '%'))
               or lower(product.brand) like lower(concat('%', :query, '%'))
               or lower(product.category) like lower(concat('%', :query, '%'))
               or lower(product.subcategory) like lower(concat('%', :query, '%'))
            """)
    Page<ProductEntity> search(@Param("query") String query, Pageable pageable);
}
