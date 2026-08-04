package com.abdul.catalogo.synchronization.repository;

import com.abdul.catalogo.synchronization.entity.SyncRecordEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

import java.util.Optional;

public interface SyncRecordRepository extends JpaRepository<SyncRecordEntity, String> {
    Optional<SyncRecordEntity> findByEntityTypeAndEntityId(String entityType, String entityId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from SyncRecordEntity r where r.entityType = :entityType and r.entityId = :entityId")
    Optional<SyncRecordEntity> findForUpdate(String entityType, String entityId);

    @Query(value = """
            SELECT * FROM sync_records r ORDER BY
            CASE r.entity_type
              WHEN 'COMPANY' THEN 10 WHEN 'BRAND' THEN 20 WHEN 'CATEGORY' THEN 30
              WHEN 'BRAND_CATEGORY' THEN 40 WHEN 'MEASUREMENT_UNIT' THEN 50
              WHEN 'CATEGORY_ATTRIBUTE' THEN 60 WHEN 'CATEGORY_ATTRIBUTE_OPTION' THEN 70
              WHEN 'CATEGORY_ATTRIBUTE_UNIT' THEN 80 WHEN 'LEGACY_ATTRIBUTE_DEFINITION' THEN 90
              WHEN 'PRODUCT' THEN 100 WHEN 'CLIENT' THEN 200 WHEN 'ORDER_SHEET' THEN 300
              WHEN 'ORDER' THEN 310 WHEN 'ORDER_ITEM' THEN 320 WHEN 'QUOTE' THEN 400
              WHEN 'QUOTE_ITEM' THEN 410 WHEN 'PREPARATION' THEN 500
              WHEN 'PREPARATION_STOCK_MOVEMENT' THEN 510 WHEN 'ORDER_LOAD' THEN 600
              WHEN 'ORDER_HISTORY' THEN 700 WHEN 'ORDER_SHEET_HISTORY' THEN 710 ELSE 9999 END,
            r.entity_id
            """, countQuery = "SELECT COUNT(*) FROM sync_records", nativeQuery = true)
    Page<SyncRecordEntity> findBootstrapPage(Pageable pageable);
}
