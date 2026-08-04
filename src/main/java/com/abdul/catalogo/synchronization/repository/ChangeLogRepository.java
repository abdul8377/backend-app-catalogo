package com.abdul.catalogo.synchronization.repository;

import com.abdul.catalogo.synchronization.entity.ChangeLogEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChangeLogRepository extends JpaRepository<ChangeLogEntity, Long> {
    List<ChangeLogEntity> findBySequenceGreaterThanOrderBySequenceAsc(long sequence, Pageable pageable);
}
