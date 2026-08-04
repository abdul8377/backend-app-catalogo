package com.abdul.catalogo.storage.repository;

import com.abdul.catalogo.storage.entity.StoredFileEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface StoredFileRepository extends JpaRepository<StoredFileEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from StoredFileEntity f where f.id = :id")
    Optional<StoredFileEntity> findForUpdate(String id);
}
