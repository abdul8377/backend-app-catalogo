package com.abdul.catalogo.product.controller;

import com.abdul.catalogo.product.dto.ProductResponse;
import com.abdul.catalogo.product.dto.ProductUpsertRequest;
import com.abdul.catalogo.product.service.ProductService;
import com.abdul.catalogo.synchronization.security.DevicePrincipal;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products")
public class ProductRestController {

    private final ProductService productService;

    public ProductRestController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    public Page<ProductResponse> list(@RequestParam(defaultValue = "") String query,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "50") int size) {
        return productService.list(query, page, size);
    }

    @GetMapping("/{id}")
    public ProductResponse get(@PathVariable String id) {
        return productService.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductResponse create(@Valid @RequestBody ProductUpsertRequest request,
                                  @AuthenticationPrincipal DevicePrincipal principal) {
        return productService.create(request, principal.deviceId());
    }

    @PutMapping("/{id}")
    public ProductResponse update(@PathVariable String id, @Valid @RequestBody ProductUpsertRequest request,
                                  @AuthenticationPrincipal DevicePrincipal principal) {
        return productService.update(id, request, principal.deviceId());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, @AuthenticationPrincipal DevicePrincipal principal) {
        productService.delete(id, principal.deviceId());
    }
}
