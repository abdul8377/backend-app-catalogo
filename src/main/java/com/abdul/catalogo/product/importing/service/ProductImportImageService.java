package com.abdul.catalogo.product.importing.service;

import com.abdul.catalogo.product.importing.entity.ProductImportEntity;
import com.abdul.catalogo.shared.exception.BusinessRuleException;
import com.abdul.catalogo.storage.StorageService;
import com.abdul.catalogo.storage.StoredFileService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class ProductImportImageService {
    private static final long MAX_UNCOMPRESSED_BYTES = 500L * 1024L * 1024L;
    private static final int MAX_IMAGES = 10_000;
    private final StorageService storageService;
    private final StoredFileService storedFileService;
    private final ObjectMapper objectMapper;

    public ProductImportImageService(StorageService storageService, StoredFileService storedFileService,
                                     ObjectMapper objectMapper) {
        this.storageService = storageService;
        this.storedFileService = storedFileService;
        this.objectMapper = objectMapper;
    }

    public Set<String> inspect(byte[] zipBytes) {
        if (zipBytes == null || zipBytes.length == 0) return Set.of();
        return Set.copyOf(readEntries(zipBytes).keySet());
    }

    public ObjectNode previewAggregate(ObjectNode aggregate) {
        ObjectNode copy = aggregate.deepCopy();
        JsonNode rawImages = copy.path("images");
        if (!(rawImages instanceof ArrayNode images)) return copy;
        for (int index = 0; index < images.size(); index++) {
            if (!(images.get(index) instanceof ObjectNode image)) continue;
            String source = image.path("sourceFile").asText("").trim();
            if (source.isBlank()) continue;
            image.put("storageKey", "files/00000000-0000-0000-0000-000000000000/content");
            image.put("type", "PRODUCT");
        }
        return copy;
    }

    public ObjectNode materialize(ProductImportEntity importItem, String productId, ObjectNode aggregate) {
        ObjectNode copy = aggregate.deepCopy();
        JsonNode rawImages = copy.path("images");
        if (!(rawImages instanceof ArrayNode images) || images.isEmpty()) return copy;

        Map<String, ArchiveImage> archive = archive(importItem);
        boolean hasPrimary = false;
        for (int index = 0; index < images.size(); index++) {
            if (!(images.get(index) instanceof ObjectNode image)) continue;
            String source = normalize(image.path("sourceFile").asText(""));
            if (!source.isBlank()) {
                ArchiveImage entry = archive.get(source);
                if (entry == null) {
                    throw new BusinessRuleException("IMPORT_IMAGE_MISSING",
                            "No se encontró la imagen " + image.path("sourceFile").asText() + " dentro del ZIP.");
                }
                var stored = storedFileService.storeReadyProductImage(entry.fileName(), entry.contentType(), entry.bytes(), productId);
                image.put("id", stored.fileId());
                image.put("storageKey", stored.storageKey());
                image.put("url", stored.downloadUrl());
                image.put("type", "PRODUCT");
                image.remove("sourceFile");
            }
            if (image.path("primary").asBoolean(false)) {
                if (!hasPrimary) hasPrimary = true;
                else image.put("primary", false);
            }
        }
        if (!hasPrimary && !images.isEmpty() && images.get(0) instanceof ObjectNode first) first.put("primary", true);

        ObjectNode imageConfiguration = copy.path("imageConfiguration").isObject()
                ? (ObjectNode) copy.path("imageConfiguration") : objectMapper.createObjectNode();
        imageConfiguration.set("remote_images", images.deepCopy());
        copy.set("imageConfiguration", imageConfiguration);
        return copy;
    }

    private Map<String, ArchiveImage> archive(ProductImportEntity item) {
        if (item.getImageArchiveStorageKey() == null || item.getImageArchiveStorageKey().isBlank()) return Map.of();
        try (var input = storageService.load(item.getImageArchiveStorageKey()).getInputStream()) {
            return readEntries(input.readAllBytes());
        } catch (IOException exception) {
            throw new BusinessRuleException("IMPORT_IMAGE_ARCHIVE_READ_ERROR",
                    "No se pudo leer el ZIP de imágenes de la importación.");
        }
    }

    private Map<String, ArchiveImage> readEntries(byte[] zipBytes) {
        Map<String, ArchiveImage> result = new LinkedHashMap<>();
        Set<String> duplicateNames = new LinkedHashSet<>();
        long total = 0;
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String original = safeEntry(entry.getName());
                String normalized = normalize(original);
                if (normalized.isBlank() || hidden(original)) continue;
                String contentType = contentType(original);
                byte[] bytes = input.readAllBytes();
                total += bytes.length;
                if (total > MAX_UNCOMPRESSED_BYTES) {
                    throw new BusinessRuleException("IMPORT_IMAGE_ARCHIVE_TOO_LARGE",
                            "Las imágenes descomprimidas superan 500 MB.");
                }
                if (result.size() >= MAX_IMAGES) {
                    throw new BusinessRuleException("IMPORT_IMAGE_COUNT_LIMIT",
                            "El ZIP supera el máximo de " + MAX_IMAGES + " imágenes.");
                }
                if (result.containsKey(normalized)) duplicateNames.add(original);
                else result.put(normalized, new ArchiveImage(original, contentType, bytes));
            }
        } catch (IOException exception) {
            throw new BusinessRuleException("INVALID_IMAGE_ZIP", "No se pudo abrir el ZIP de imágenes.");
        }
        if (!duplicateNames.isEmpty()) {
            throw new BusinessRuleException("DUPLICATE_IMAGE_NAME",
                    "El ZIP contiene nombres de imagen repetidos: " + String.join(", ", duplicateNames) + ".");
        }
        return result;
    }

    private String safeEntry(String name) {
        String normalized = name == null ? "" : name.replace('\\', '/').trim();
        if (normalized.startsWith("/") || normalized.contains("../") || normalized.equals("..") || normalized.contains(":")) {
            throw new BusinessRuleException("INVALID_IMAGE_ZIP_PATH", "El ZIP contiene una ruta no permitida.");
        }
        return normalized;
    }

    private String contentType(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        throw new BusinessRuleException("IMPORT_IMAGE_TYPE_NOT_ALLOWED",
                "Solo se aceptan imágenes JPG, PNG o WebP: " + fileName + ".");
    }

    private boolean hidden(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("__macosx/") || lower.endsWith(".ds_store") || lower.endsWith("thumbs.db");
    }

    private String normalize(String value) {
        return value == null ? "" : value.replace('\\', '/').trim().toLowerCase(Locale.ROOT);
    }

    private record ArchiveImage(String fileName, String contentType, byte[] bytes) {}
}
