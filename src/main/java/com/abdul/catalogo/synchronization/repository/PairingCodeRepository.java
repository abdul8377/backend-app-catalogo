package com.abdul.catalogo.synchronization.repository;

import com.abdul.catalogo.synchronization.entity.PairingCodeEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;
import java.util.List;
import java.time.Instant;
import com.abdul.catalogo.synchronization.model.PairingCodeStatus;

public interface PairingCodeRepository extends JpaRepository<PairingCodeEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PairingCodeEntity> findByCodeHash(String codeHash);
    List<PairingCodeEntity> findByStatusAndExpiresAtBefore(PairingCodeStatus status, Instant before);
    boolean existsByStatusAndExpiresAtAfter(PairingCodeStatus status, Instant after);
}
