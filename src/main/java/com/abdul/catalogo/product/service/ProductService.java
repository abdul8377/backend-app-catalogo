package com.abdul.catalogo.product.service;

import com.abdul.catalogo.catalog.service.CatalogMasterDataService;
import com.abdul.catalogo.product.dto.ProductResponse;
import com.abdul.catalogo.product.dto.ProductUpsertRequest;
import com.abdul.catalogo.product.entity.ProductEntity;
import com.abdul.catalogo.product.model.ProductStatus;
import com.abdul.catalogo.product.model.ProductType;
import com.abdul.catalogo.product.repository.ProductRepository;
import com.abdul.catalogo.shared.exception.ResourceNotFoundException;
import com.abdul.catalogo.synchronization.model.SyncOperation;
import com.abdul.catalogo.synchronization.service.ServerChangePublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Optional;
import java.util.UUID;

@Service
public class ProductService {
    private final ProductRepository productRepository;
    private final ProductProjectionService projectionService;
    private final ServerChangePublisher changePublisher;
    private final CatalogMasterDataService masterDataService;
    private final ObjectMapper objectMapper;

    public ProductService(ProductRepository productRepository, ProductProjectionService projectionService,
                          ServerChangePublisher changePublisher, CatalogMasterDataService masterDataService,
                          ObjectMapper objectMapper) {
        this.productRepository = productRepository;
        this.projectionService = projectionService;
        this.changePublisher = changePublisher;
        this.masterDataService = masterDataService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Page<ProductResponse> list(String query, int page, int size) {
        var pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.ASC, "name"));
        Page<ProductEntity> products = query == null || query.isBlank()
                ? productRepository.findAll(pageable)
                : productRepository.search(query.trim(), pageable);
        return products.map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public ProductResponse get(String id) {
        return toResponse(requireProduct(id));
    }

    @Transactional(readOnly = true)
    public Optional<ProductResponse> findByCode(String code) {
        return productRepository.findByCodeIgnoreCase(code).map(this::toResponse);
    }

    @Transactional
    public ProductResponse create(ProductUpsertRequest request, String origin) {
        return publishCreate(aggregate(request, UUID.randomUUID().toString(), objectMapper.createObjectNode()), origin);
    }

    @Transactional
    public ProductResponse createAggregate(ObjectNode aggregate, String origin) {
        String id = textOr(aggregate, "productId", UUID.randomUUID().toString());
        aggregate.put("productId", id);
        normalizeAggregate(aggregate);
        return publishCreate(aggregate, origin);
    }

    @Transactional
    public ProductResponse update(String id, ProductUpsertRequest request, String origin) {
        ProductEntity current = requireProduct(id);
        long expected = request.version() == null ? current.getVersion() : request.version();
        ObjectNode existingAggregate = readObject(current.getAggregateJson());
        return publishUpdate(id, expected, aggregate(request, id, existingAggregate), origin);
    }

    @Transactional
    public ProductResponse updateAggregate(String id, long expectedVersion, ObjectNode aggregate, String origin) {
        aggregate.put("productId", id);
        normalizeAggregate(aggregate);
        return publishUpdate(id, expectedVersion, aggregate, origin);
    }

    @Transactional
    public ProductResponse deactivate(String id, String origin) {
        ProductEntity current = requireProduct(id);
        ObjectNode aggregate = readObject(current.getAggregateJson());
        normalizeAggregate(aggregate);
        aggregate.put("status", ProductStatus.INACTIVE.name());
        return publishUpdate(id, current.getVersion(), aggregate, origin);
    }

    @Transactional
    public void delete(String id, String origin) {
        ProductEntity current = requireProduct(id);
        ObjectNode aggregate = readObject(current.getAggregateJson());
        normalizeAggregate(aggregate);
        aggregate.put("status", ProductStatus.DELETED.name());
        var published = changePublisher.publish(
                "PRODUCT", id, SyncOperation.DELETE, aggregate, origin, current.getVersion());
        projectionService.apply(id, aggregate, published.version(), origin, true);
    }

    private ProductResponse publishCreate(ObjectNode aggregate, String origin) {
        normalizeAggregate(aggregate);
        masterDataService.canonicalizeRequired(aggregate);
        String id = aggregate.path("productId").asText();
        projectionService.validateUpsert(id, aggregate);
        var published = changePublisher.publish("PRODUCT", id, SyncOperation.UPSERT, aggregate, origin, 0L);
        projectionService.apply(id, aggregate, published.version(), origin, false);
        return toResponse(requireProduct(id));
    }

    private ProductResponse publishUpdate(String id, long expectedVersion, ObjectNode aggregate, String origin) {
        requireProduct(id);
        normalizeAggregate(aggregate);
        masterDataService.canonicalizeRequired(aggregate);
        projectionService.validateUpsert(id, aggregate);
        var published = changePublisher.publish(
                "PRODUCT", id, SyncOperation.UPSERT, aggregate, origin, expectedVersion);
        projectionService.apply(id, aggregate, published.version(), origin, false);
        return toResponse(requireProduct(id));
    }

    private ObjectNode aggregate(ProductUpsertRequest request, String id, ObjectNode baseAggregate) {
        ObjectNode node = baseAggregate == null ? objectMapper.createObjectNode() : baseAggregate.deepCopy();
        if (request.details() != null && request.details().isObject()) {
            request.details().properties().forEach(entry -> node.set(entry.getKey(), entry.getValue().deepCopy()));
        }
        node.put("productId", id);
        node.put("code", request.code().trim());
        node.put("name", request.name().trim());
        node.put("description", safe(request.description()));
        node.put("company", safe(request.company()));
        node.put("companyId", safe(request.companyId()));
        node.put("brand", safe(request.brand()));
        node.put("brandId", safe(request.brandId()));
        node.put("category", safe(request.category()));
        node.put("categoryId", safe(request.categoryId()));
        node.put("subcategory", safe(request.subcategory()));
        node.put("subcategoryId", safe(request.subcategoryId()));
        node.put("productType", request.productType().name());
        node.put("status", request.status().name());
        normalizeAggregate(node);
        return node;
    }

    private void normalizeAggregate(ObjectNode node) {
        node.putIfAbsent("attributes", objectMapper.createObjectNode());
        node.putIfAbsent("variants", objectMapper.createArrayNode());
        node.putIfAbsent("presentations", objectMapper.createArrayNode());
        node.putIfAbsent("prices", objectMapper.createArrayNode());
        node.putIfAbsent("images", objectMapper.createArrayNode());
        node.putIfAbsent("familyAxes", objectMapper.createArrayNode());
        node.putIfAbsent("attributeValues", objectMapper.createArrayNode());
        node.putIfAbsent("attributeOptions", objectMapper.createArrayNode());
        node.putIfAbsent("salesConfiguration", objectMapper.createObjectNode());
        node.putIfAbsent("pricingConfiguration", objectMapper.createObjectNode());
        node.putIfAbsent("imageConfiguration", objectMapper.createObjectNode());
        node.putIfAbsent("productType", objectMapper.getNodeFactory().textNode(ProductType.SINGLE.name()));
        node.putIfAbsent("status", objectMapper.getNodeFactory().textNode(ProductStatus.DRAFT.name()));
        ensureText(node, "company");
        ensureText(node, "companyId");
        ensureText(node, "brand");
        ensureText(node, "brandId");
        ensureText(node, "category");
        ensureText(node, "categoryId");
        ensureText(node, "subcategory");
        ensureText(node, "subcategoryId");
        masterDataService.canonicalizeIfResolvable(node);
    }

    private void ensureText(ObjectNode node, String field) {
        if (!node.has(field) || node.get(field).isNull()) {
            node.put(field, "");
        } else if (!node.get(field).isTextual()) {
            node.put(field, node.get(field).asText(""));
        }
    }

    private ProductEntity requireProduct(String id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("PRODUCT_NOT_FOUND", "El producto no existe."));
    }

    private ProductResponse toResponse(ProductEntity product) {
        return new ProductResponse(product.getId(), product.getCode(), product.getName(), product.getDescription(),
                product.getCompany(), product.getCompanyId(), product.getBrand(), product.getBrandId(),
                product.getCategory(), product.getCategoryId(), product.getSubcategory(), product.getSubcategoryId(),
                product.getProductType(), product.getStatus(), product.getVersion(), readObject(product.getAggregateJson()),
                product.getCreatedAt(), product.getUpdatedAt());
    }

    private ObjectNode readObject(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            return node != null && node.isObject() ? (ObjectNode) node : objectMapper.createObjectNode();
        } catch (JacksonException exception) {
            throw new IllegalStateException("El agregado del producto contiene JSON inválido.", exception);
        }
    }

    private String textOr(ObjectNode node, String field, String fallback) {
        String value = node.path(field).asText("").trim();
        return value.isBlank() ? fallback : value;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
