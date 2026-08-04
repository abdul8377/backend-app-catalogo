package com.abdul.catalogo.storage;

import com.abdul.catalogo.shared.config.StorageProperties;
import com.abdul.catalogo.shared.exception.BusinessRuleException;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Service
public class LocalFileStorageService implements StorageService {
    private final StorageProperties properties;
    private Path root;

    public LocalFileStorageService(StorageProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void initialize() throws IOException {
        root = properties.root().toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    @Override
    public String store(String relativeKey, InputStream input, long size) throws IOException {
        if (size < 0 || size > properties.maxFileSizeBytes()) {
            throw new BusinessRuleException("FILE_SIZE_NOT_ALLOWED", "El archivo supera el tamaño permitido.");
        }
        Path target = resolve(relativeKey);
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".uploading");
        try (InputStream source = input) {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
        }
        if (Files.size(temporary) != size) {
            Files.deleteIfExists(temporary);
            throw new BusinessRuleException("FILE_SIZE_MISMATCH", "El tamaño recibido no coincide con el declarado.");
        }
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return root.relativize(target).toString().replace('\\', '/');
    }

    @Override
    public Resource load(String relativeKey) {
        Path target = resolve(relativeKey);
        if (!Files.isRegularFile(target)) {
            throw new BusinessRuleException("FILE_NOT_FOUND", "El archivo solicitado no existe.");
        }
        return new FileSystemResource(target);
    }

    @Override
    public void delete(String relativeKey) throws IOException {
        Files.deleteIfExists(resolve(relativeKey));
    }

    private Path resolve(String relativeKey) {
        if (relativeKey == null || relativeKey.isBlank()) {
            throw new BusinessRuleException("INVALID_STORAGE_KEY", "La clave de almacenamiento está vacía.");
        }
        Path target = root.resolve(relativeKey.replace('/', java.io.File.separatorChar)).normalize();
        if (!target.startsWith(root)) {
            throw new BusinessRuleException("INVALID_STORAGE_KEY", "La clave intenta salir del almacenamiento local.");
        }
        return target;
    }
}
