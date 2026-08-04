package com.abdul.catalogo.product.service;

import com.abdul.catalogo.product.dto.ProductResponse;
import com.abdul.catalogo.product.dto.ProductUpsertRequest;
import com.abdul.catalogo.product.entity.ProductEntity;
import com.abdul.catalogo.product.model.ProductStatus;
import com.abdul.catalogo.product.repository.ProductRepository;
import com.abdul.catalogo.shared.exception.BusinessRuleException;
import com.abdul.catalogo.shared.exception.ResourceNotFoundException;
import com.abdul.catalogo.synchronization.model.SyncOperation;
import com.abdul.catalogo.synchronization.service.ServerChangePublisher;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductProjectionService projectionService;
    private final ServerChangePublisher changePublisher;
    private final ObjectMapper objectMapper;

    public ProductService(ProductRepository productRepository, ProductProjectionService projectionService,
                          ServerChangePublisher changePublisher, ObjectMapper objectMapper) {
        this.productRepository = productRepository;
        this.projectionService = projectionService;
        this.changePublisher = changePublisher;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Page<ProductResponse> list(String query, int page, int size) {
        var pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.ASC, "name"));
        Page<ProductEntity> products = query == null || query.isBlank()
                ? productRepository.findAll(pageable)
                : productRepository.findByNameContainingIgnoreCaseOrCodeContainingIgnoreCase(query.trim(), query.trim(), pageable);
        return products.map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public ProductResponse get(String id) {
        return toResponse(requireProduct(id));
    }

    @Transactional
    public ProductResponse create(ProductUpsertRequest request, String origin) {
        String id = UUID.randomUUID().toString();
        ObjectNode aggregate = aggregate(request, id);
        projectionService.validateUpsert(id, aggregate);
        var published = changePublisher.publish("PRODUCT", id, SyncOperation.UPSERT, aggregate, origin);
        projectionService.apply(id, aggregate, published.version(), origin, false);
        return toResponse(requireProduct(id));
    }

    @Transactional
    public ProductResponse update(String id, ProductUpsertRequest request, String origin) {
        ProductEntity current = requireProduct(id);
        if (request.version() != null && request.version() != current.getVersion()) {
            throw new BusinessRuleException("PRODUCT_VERSION_CONFLICT",
                    "El producto fue modificado después de abrir el formulario.");
        }
        ObjectNode aggregate = aggregate(request, id);
        projectionService.validateUpsert(id, aggregate);
        var published = changePublisher.publish("PRODUCT", id, SyncOperation.UPSERT, aggregate, origin);
        projectionService.apply(id, aggregate, published.version(), origin, false);
        return toResponse(requireProduct(id));
    }

    @Transactional
    public ProductResponse deactivate(String id, String origin) {
        ProductEntity current = requireProduct(id);
        ObjectNode aggregate = readObject(current.getAggregateJson());
        aggregate.put("status", ProductStatus.INACTIVE.name());
        var published = changePublisher.publish("PRODUCT", id, SyncOperation.UPSERT, aggregate, origin);
        projectionService.apply(id, aggregate, published.version(), origin, false);
        return toResponse(requireProduct(id));
    }

    @Transactional
    public void delete(String id, String origin) {
        ProductEntity current = requireProduct(id);
        ObjectNode aggregate = readObject(current.getAggregateJson());
        aggregate.put("status", ProductStatus.DELETED.name());
        var published = changePublisher.publish("PRODUCT", id, SyncOperation.DELETE, aggregate, origin);
        projectionService.apply(id, aggregate, published.version(), origin, true);
    }

    private ObjectNode aggregate(ProductUpsertRequest request, String id) {
        ObjectNode node = request.details() != null && request.details().isObject()
                ? ((ObjectNode) request.details()).deepCopy()
                : objectMapper.createObjectNode();
        node.put("productId", id);
        node.put("code", request.code().trim());
        node.put("name", request.name().trim());
        node.put("description", safe(request.description()));
        node.put("company", safe(request.company()));
        node.put("brand", safe(request.brand()));
        node.put("category", safe(request.category()));
        node.put("status", request.status().name());
        return node;
    }

    private ProductEntity requireProduct(String id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("PRODUCT_NOT_FOUND", "El producto no existe."));
    }

    private ProductResponse toResponse(ProductEntity product) {
        return new ProductResponse(product.getId(), product.getCode(), product.getName(), product.getDescription(),
                product.getCompany(), product.getBrand(), product.getCategory(), product.getStatus(), product.getVersion(),
                readObject(product.getAggregateJson()), product.getCreatedAt(), product.getUpdatedAt());
    }

    private ObjectNode readObject(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            return node != null && node.isObject() ? (ObjectNode) node : objectMapper.createObjectNode();
        } catch (JacksonException exception) {
            throw new IllegalStateException("El agregado del producto contiene JSON inválido.", exception);
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
