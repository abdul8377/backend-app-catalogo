package com.abdul.catalogo.product.importing.repository;

import com.abdul.catalogo.product.importing.entity.ProductImportEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.List;
import java.time.Instant;
import com.abdul.catalogo.product.importing.model.ProductImportStatus;

public interface ProductImportRepository extends JpaRepository<ProductImportEntity, String> {
    Optional<ProductImportEntity> findByFileHash(String fileHash);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from ProductImportEntity i where i.id = :id")
    Optional<ProductImportEntity> findForUpdate(String id);
    List<ProductImportEntity> findByCreatedAtBeforeAndStatusIn(Instant before, List<ProductImportStatus> statuses);
}
