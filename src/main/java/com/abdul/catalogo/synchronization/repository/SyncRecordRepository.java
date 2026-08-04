package com.abdul.catalogo.synchronization.repository;

import com.abdul.catalogo.synchronization.entity.SyncRecordEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SyncRecordRepository extends JpaRepository<SyncRecordEntity, String> {
    Optional<SyncRecordEntity> findByEntityTypeAndEntityId(String entityType, String entityId);
    Page<SyncRecordEntity> findAllByOrderByEntityTypeAscEntityIdAsc(Pageable pageable);
}
