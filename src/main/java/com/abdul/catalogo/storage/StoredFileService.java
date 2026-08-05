package com.abdul.catalogo.storage;

import com.abdul.catalogo.shared.config.StorageProperties;
import com.abdul.catalogo.shared.crypto.Digests;
import com.abdul.catalogo.shared.exception.BusinessRuleException;
import com.abdul.catalogo.shared.exception.ResourceNotFoundException;
import com.abdul.catalogo.storage.dto.FileIntentRequest;
import com.abdul.catalogo.storage.dto.FileIntentResponse;
import com.abdul.catalogo.storage.dto.StoredFileResponse;
import com.abdul.catalogo.storage.entity.StoredFileEntity;
import com.abdul.catalogo.storage.model.FileVisibility;
import com.abdul.catalogo.storage.model.StoredFileStatus;
import com.abdul.catalogo.storage.model.StoredFileType;
import com.abdul.catalogo.storage.repository.StoredFileRepository;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Service
public class StoredFileService {
    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/webp", "application/pdf");
    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private final StoredFileRepository repository;
    private final StorageService storageService;
    private final StorageProperties properties;

    public StoredFileService(StoredFileRepository repository, StorageService storageService, StorageProperties properties) {
        this.repository = repository; this.storageService = storageService; this.properties = properties;
    }

    @Transactional
    public FileIntentResponse createIntent(FileIntentRequest request) {
        validateIntent(request);
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        StoredFileEntity file = new StoredFileEntity(); file.setId(id); file.setStorageKey("files/" + id + "/content");
        file.setFileType(request.fileType());
        file.setOriginalName(safeName(request.fileName())); file.setContentType(request.contentType().toLowerCase());
        file.setSizeBytes(request.sizeBytes()); file.setChecksumSha256(request.checksumSha256().toLowerCase());
        file.setVisibility(request.visibility()); file.setStatus(StoredFileStatus.INTENT);
        file.setOwnerType(normalize(request.ownerType())); file.setOwnerId(request.ownerId().trim()); file.setCreatedAt(now);
        file.setExpiresAt(now.plus(properties.intentDuration()));
        repository.save(file);
        return new FileIntentResponse(id, file.getStorageKey(), file.getFileType(), file.getOwnerType(), file.getOwnerId(),
                file.getContentType(), file.getSizeBytes(), file.getChecksumSha256(), file.getVisibility(), file.getStatus(),
                file.getExpiresAt(), "/api/v1/files/intents/" + id + "/content",
                "/api/v1/files/intents/" + id + "/complete");
    }

    /**
     * Carga confiable desde una pantalla administrativa o una importación.
     * No usa intent porque el archivo ya se encuentra dentro del servidor.
     */
    @Transactional
    public StoredFileResponse storeReadyProductImage(MultipartFile upload, String ownerId) {
        if (upload == null || upload.isEmpty()) {
            throw new BusinessRuleException("EMPTY_PRODUCT_IMAGE", "Selecciona una imagen válida.");
        }
        try {
            return storeReadyProductImage(safeName(upload.getOriginalFilename()), upload.getContentType(), upload.getBytes(), ownerId);
        } catch (IOException exception) {
            throw new BusinessRuleException("FILE_READ_ERROR", "No se pudo leer la imagen seleccionada.");
        }
    }

    @Transactional
    public StoredFileResponse storeReadyProductImage(String fileName, String contentType, byte[] bytes, String ownerId) {
        String mime = contentType == null ? "" : contentType.toLowerCase().trim();
        if (!IMAGE_TYPES.contains(mime)) {
            throw new BusinessRuleException("FILE_MIME_NOT_ALLOWED", "Las imágenes deben ser JPG, PNG o WebP.");
        }
        if (bytes == null || bytes.length == 0) {
            throw new BusinessRuleException("EMPTY_PRODUCT_IMAGE", "La imagen está vacía.");
        }
        if (bytes.length > properties.maxFileSizeBytes()) {
            throw new BusinessRuleException("FILE_TOO_LARGE", "La imagen supera el máximo permitido.");
        }
        String safeOwner = ownerId == null ? "" : ownerId.trim();
        if (safeOwner.isBlank()) {
            throw new BusinessRuleException("PRODUCT_IMAGE_OWNER_REQUIRED", "La imagen necesita un producto propietario.");
        }

        String id = UUID.randomUUID().toString();
        String key = "files/" + id + "/content";
        String checksum = Digests.sha256(bytes);
        Instant now = Instant.now();
        try {
            storageService.store(key, new ByteArrayInputStream(bytes), bytes.length);
        } catch (IOException exception) {
            throw new BusinessRuleException("FILE_STORAGE_ERROR", "No se pudo guardar la imagen en el disco.");
        }

        StoredFileEntity file = new StoredFileEntity();
        file.setId(id);
        file.setStorageKey(key);
        file.setFileType(StoredFileType.PRODUCT_IMAGE);
        file.setOriginalName(safeName(fileName));
        file.setContentType(mime);
        file.setSizeBytes((long) bytes.length);
        file.setChecksumSha256(checksum);
        file.setVisibility(FileVisibility.PUBLIC);
        file.setStatus(StoredFileStatus.READY);
        file.setOwnerType("PRODUCT");
        file.setOwnerId(safeOwner);
        file.setCreatedAt(now);
        file.setUploadedAt(now);
        file.setCompletedAt(now);
        repository.saveAndFlush(file);
        return response(file);
    }

    @Transactional
    public StoredFileResponse upload(String id, MultipartFile upload) {
        StoredFileEntity file = requireForUpdate(id);
        if (file.getStatus() == StoredFileStatus.READY) return response(file);
        requireActive(file);
        if (upload.getSize() != file.getSizeBytes()) throw new BusinessRuleException("FILE_SIZE_MISMATCH", "El tamaño no coincide con el intent.");
        if (!file.getContentType().equalsIgnoreCase(upload.getContentType())) throw new BusinessRuleException("FILE_MIME_MISMATCH", "El tipo MIME no coincide con el intent.");
        try {
            byte[] bytes = upload.getBytes();
            if (!Digests.sha256(bytes).equalsIgnoreCase(file.getChecksumSha256())) throw new BusinessRuleException("FILE_CHECKSUM_MISMATCH", "El checksum no coincide con el intent.");
            storageService.store(file.getStorageKey(), new ByteArrayInputStream(bytes), bytes.length);
        } catch (IOException exception) {
            throw new BusinessRuleException("FILE_STORAGE_ERROR", "No se pudo guardar el archivo.");
        }
        file.setStatus(StoredFileStatus.UPLOADED); file.setUploadedAt(Instant.now());
        return response(file);
    }

    @Transactional
    public StoredFileResponse complete(String id) {
        StoredFileEntity file = requireForUpdate(id);
        if (file.getStatus() == StoredFileStatus.READY) return response(file);
        requireActive(file);
        if (file.getStatus() == StoredFileStatus.INTENT) throw new BusinessRuleException("FILE_NOT_UPLOADED", "Primero debe cargarse el contenido.");
        file.setStatus(StoredFileStatus.READY); file.setCompletedAt(Instant.now()); return response(file);
    }

    @Transactional(readOnly = true)
    public Download download(String id, boolean publicRequest) {
        StoredFileEntity file = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("FILE_NOT_FOUND", "El archivo no existe."));
        if (file.getStatus() != StoredFileStatus.READY) throw new BusinessRuleException("FILE_NOT_READY", "El archivo todavía no está disponible.");
        if (publicRequest && file.getVisibility() != FileVisibility.PUBLIC) throw new ResourceNotFoundException("FILE_NOT_FOUND", "El archivo no existe.");
        return new Download(file.getOriginalName(), file.getContentType(), storageService.load(file.getStorageKey()));
    }

    private void validateIntent(FileIntentRequest request) {
        if (request.sizeBytes() > properties.maxFileSizeBytes()) throw new BusinessRuleException("FILE_TOO_LARGE", "El archivo supera el máximo permitido.");
        if (!ALLOWED_TYPES.contains(request.contentType().toLowerCase())) throw new BusinessRuleException("FILE_MIME_NOT_ALLOWED", "Tipo de archivo no permitido.");
        String owner = normalize(request.ownerType());
        boolean productImage = request.fileType() == StoredFileType.PRODUCT_IMAGE
                || request.fileType() == StoredFileType.PRODUCT_THUMBNAIL;
        if (productImage && !request.contentType().toLowerCase().startsWith("image/")) {
            throw new BusinessRuleException("FILE_TYPE_MISMATCH", "Los archivos de producto deben ser imágenes.");
        }
        if (request.visibility() == FileVisibility.PUBLIC && (!productImage || !owner.equals("PRODUCT"))) {
            throw new BusinessRuleException("PUBLIC_FILE_NOT_ALLOWED", "Solo imágenes de producto pueden ser públicas.");
        }
    }

    private StoredFileEntity requireForUpdate(String id) { return repository.findForUpdate(id).orElseThrow(() -> new ResourceNotFoundException("FILE_NOT_FOUND", "El archivo no existe.")); }
    private void requireActive(StoredFileEntity file) {
        if (file.getStatus() == StoredFileStatus.EXPIRED
                || (file.getExpiresAt() != null && !file.getExpiresAt().isAfter(Instant.now()))) {
            throw new BusinessRuleException("FILE_INTENT_EXPIRED", "El intent de carga expiró; solicita uno nuevo.");
        }
    }
    private StoredFileResponse response(StoredFileEntity file) { return new StoredFileResponse(file.getId(), file.getStorageKey(), file.getFileType(), file.getOwnerType(), file.getOwnerId(), file.getVisibility() == FileVisibility.PUBLIC ? "/public/files/" + file.getId() : "/api/v1/files/" + file.getId(), file.getContentType(), file.getSizeBytes(), file.getChecksumSha256(), file.getVisibility(), file.getStatus(), file.getCreatedAt(), file.getExpiresAt(), file.getUploadedAt(), file.getCompletedAt()); }
    private String safeName(String name) {
        String value = name == null || name.isBlank() ? "imagen" : name;
        return Path.of(value.replace('\\', '/')).getFileName().toString();
    }
    private String normalize(String value) { return value == null ? "" : value.trim().toUpperCase(); }
    public record Download(String fileName, String contentType, Resource resource) {}
}
