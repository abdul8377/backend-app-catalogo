package com.abdul.catalogo.synchronization.repository;

import com.abdul.catalogo.synchronization.entity.DeviceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeviceRepository extends JpaRepository<DeviceEntity, String> {
    List<DeviceEntity> findAllByOrderByCreatedAtDesc();
}
