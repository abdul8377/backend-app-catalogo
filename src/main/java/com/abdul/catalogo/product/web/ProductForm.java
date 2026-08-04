package com.abdul.catalogo.product.web;

import com.abdul.catalogo.product.model.ProductStatus;
import com.abdul.catalogo.product.model.ProductType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProductForm {
    @NotBlank
    @Size(max = 100)
    private String code;

    @NotBlank
    @Size(max = 250)
    private String name;

    @Size(max = 2000)
    private String description = "";

    @Size(max = 160)
    private String company = "";

    @Size(max = 160)
    private String companyId = "";

    @Size(max = 160)
    private String brand = "";

    @Size(max = 160)
    private String brandId = "";

    @Size(max = 160)
    private String category = "";

    @Size(max = 160)
    private String categoryId = "";

    @Size(max = 160)
    private String subcategory = "";

    @Size(max = 160)
    private String subcategoryId = "";

    @NotNull
    private ProductType productType = ProductType.SINGLE;

    private String attributesJson = "{}";
    private String variantsJson = "[]";
    private String presentationsJson = "[]";
    private String pricesJson = "[]";
    private String imagesJson = "[]";

    @NotNull
    private ProductStatus status = ProductStatus.ACTIVE;

    @Min(0)
    private Long version = 0L;
}
