package com.abdul.catalogo.synchronization.repository;

import com.abdul.catalogo.synchronization.entity.SyncRecordEntity;
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

}
