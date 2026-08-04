package com.abdul.catalogo.synchronization.repository;

import com.abdul.catalogo.synchronization.entity.ServerIdentityEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServerIdentityRepository extends JpaRepository<ServerIdentityEntity, Short> {
}
