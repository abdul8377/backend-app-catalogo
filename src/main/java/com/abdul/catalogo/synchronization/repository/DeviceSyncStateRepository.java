package com.abdul.catalogo.synchronization.repository;

import com.abdul.catalogo.synchronization.entity.DeviceSyncStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceSyncStateRepository extends JpaRepository<DeviceSyncStateEntity, String> {
}
