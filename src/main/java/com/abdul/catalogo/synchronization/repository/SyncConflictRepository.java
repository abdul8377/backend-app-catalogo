package com.abdul.catalogo.synchronization.repository;

import com.abdul.catalogo.synchronization.entity.SyncConflictEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import com.abdul.catalogo.synchronization.model.ConflictStatus;

public interface SyncConflictRepository extends JpaRepository<SyncConflictEntity, String> {
    long countByStatus(ConflictStatus status);
}
