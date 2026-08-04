package com.abdul.catalogo.synchronization.repository;

import com.abdul.catalogo.synchronization.entity.SyncConflictEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import com.abdul.catalogo.synchronization.model.ConflictStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SyncConflictRepository extends JpaRepository<SyncConflictEntity, String> {
    long countByStatus(ConflictStatus status);
    List<SyncConflictEntity> findByStatusOrderByCreatedAtDesc(ConflictStatus status);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from SyncConflictEntity c where c.id = :id")
    Optional<SyncConflictEntity> findForUpdate(String id);
}
