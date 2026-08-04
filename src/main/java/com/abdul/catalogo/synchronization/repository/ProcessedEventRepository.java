package com.abdul.catalogo.synchronization.repository;

import com.abdul.catalogo.synchronization.entity.ProcessedEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEventEntity, String> {
}
