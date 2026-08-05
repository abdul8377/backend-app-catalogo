package com.abdul.catalogo.product.service;

import com.abdul.catalogo.product.web.ProductForm;
import com.abdul.catalogo.shared.exception.BusinessRuleException;
import com.abdul.catalogo.storage.StoredFileService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

@Service
public class ProductFormImageService {
    private final StoredFileService storedFileService;
    private final ObjectMapper objectMapper;

    public ProductFormImageService(StoredFileService storedFileService, ObjectMapper objectMapper) {
        this.storedFileService = storedFileService;
        this.objectMapper = objectMapper;
    }

    public void attachUploads(ProductForm form) {
        ArrayNode images = readImages(form.getImagesJson());
        List<MultipartFile> uploads = form.getImageFiles() == null
                ? List.of()
                : form.getImageFiles().stream().filter(file -> file != null && !file.isEmpty()).toList();
        if (uploads.isEmpty()) {
            ensureSinglePrimary(images);
            form.setImagesJson(write(images));
            mergeImageConfiguration(form, images);
            return;
        }

        int requestedPrimary = form.getPrimaryUploadedImage() == null ? -1 : form.getPrimaryUploadedImage();
        if (requestedPrimary >= uploads.size()) requestedPrimary = -1;
        if (requestedPrimary >= 0) clearPrimary(images);
        boolean alreadyPrimary = hasPrimary(images);

        for (int index = 0; index < uploads.size(); index++) {
            MultipartFile upload = uploads.get(index);
            var stored = storedFileService.storeReadyProductImage(upload, form.getProductId());
            ObjectNode image = objectMapper.createObjectNode();
            image.put("id", stored.fileId());
            image.put("sku", "");
            image.put("storageKey", stored.storageKey());
            image.put("url", stored.downloadUrl());
            image.put("type", "PRODUCT");
            image.put("fileName", upload.getOriginalFilename() == null ? "imagen" : upload.getOriginalFilename());
            boolean primary = requestedPrimary == index || (requestedPrimary < 0 && !alreadyPrimary && index == 0);
            image.put("primary", primary);
            if (primary) alreadyPrimary = true;
            images.add(image);
        }
        ensureSinglePrimary(images);
        form.setImagesJson(write(images));
        mergeImageConfiguration(form, images);
    }

    private void mergeImageConfiguration(ProductForm form, ArrayNode images) {
        ObjectNode configuration;
        try {
            JsonNode raw = objectMapper.readTree(form.getImageConfigurationJson());
            configuration = raw != null && raw.isObject() ? (ObjectNode) raw : objectMapper.createObjectNode();
        } catch (JacksonException exception) {
            configuration = objectMapper.createObjectNode();
        }
        configuration.set("remote_images", images.deepCopy());
        form.setImageConfigurationJson(write(configuration));
    }

    private ArrayNode readImages(String value) {
        try {
            JsonNode node = objectMapper.readTree(value == null || value.isBlank() ? "[]" : value);
            if (node == null || !node.isArray()) {
                throw new BusinessRuleException("INVALID_PRODUCT_IMAGES", "La galería contiene datos inválidos.");
            }
            return (ArrayNode) node;
        } catch (JacksonException exception) {
            throw new BusinessRuleException("INVALID_PRODUCT_IMAGES", "La galería contiene datos inválidos.");
        }
    }

    private void ensureSinglePrimary(ArrayNode images) {
        boolean found = false;
        for (JsonNode node : images) {
            if (!(node instanceof ObjectNode image)) continue;
            boolean primary = image.path("primary").asBoolean(false);
            if (primary && !found) {
                found = true;
            } else if (primary) {
                image.put("primary", false);
            }
        }
        if (!found && !images.isEmpty() && images.get(0) instanceof ObjectNode first) {
            first.put("primary", true);
        }
    }

    private void clearPrimary(ArrayNode images) {
        for (JsonNode node : images) {
            if (node instanceof ObjectNode image) image.put("primary", false);
        }
    }

    private boolean hasPrimary(ArrayNode images) {
        for (JsonNode node : images) if (node.path("primary").asBoolean(false)) return true;
        return false;
    }

    private String write(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JacksonException exception) {
            throw new IllegalStateException("No se pudo serializar la galería.", exception);
        }
    }
}
