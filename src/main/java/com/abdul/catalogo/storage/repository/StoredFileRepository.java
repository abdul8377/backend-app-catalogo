package com.abdul.catalogo.storage.repository;

import com.abdul.catalogo.storage.entity.StoredFileEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.Collection;
import java.util.List;
import java.time.Instant;

import com.abdul.catalogo.storage.model.StoredFileStatus;

public interface StoredFileRepository extends JpaRepository<StoredFileEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from StoredFileEntity f where f.id = :id")
    Optional<StoredFileEntity> findForUpdate(String id);

    List<StoredFileEntity> findByStatusInAndExpiresAtBefore(Collection<StoredFileStatus> statuses, Instant expiresAt);
}
