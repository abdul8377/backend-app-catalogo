package com.abdul.catalogo.product.web;

import com.abdul.catalogo.product.model.ProductStatus;
import com.abdul.catalogo.product.model.ProductType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class ProductForm {
    @NotBlank
    @Size(max = 36)
    private String productId;

    @NotBlank
    @Size(max = 100)
    private String code;

    @NotBlank
    @Size(max = 250)
    private String name;

    @Size(max = 2000)
    private String description = "";

    @NotBlank
    @Size(max = 160)
    private String company = "";

    @Size(max = 160)
    private String companyId = "";

    @NotBlank
    @Size(max = 160)
    private String brand = "";

    @Size(max = 160)
    private String brandId = "";

    @NotBlank
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

    /** Campos serializados por el asistente visual. Nunca se editan como JSON. */
    private String attributesJson = "{}";
    private String variantsJson = "[]";
    private String presentationsJson = "[]";
    private String pricesJson = "[]";
    private String imagesJson = "[]";

    /** Configuraciones completas equivalentes a las columnas SQLite. */
    private String salesConfigurationJson = "{}";
    private String pricingConfigurationJson = "{}";
    private String imageConfigurationJson = "{}";

    /** Proyecciones técnicas recibidas desde SQLite que deben conservarse al editar. */
    private String familyAxesJson = "[]";
    private String attributeValuesJson = "[]";
    private String attributeOptionsJson = "[]";

    /** Imágenes nuevas elegidas desde la web. */
    private List<MultipartFile> imageFiles = new ArrayList<>();
    private Integer primaryUploadedImage = 0;

    @NotNull
    private ProductStatus status = ProductStatus.DRAFT;

    @Min(0)
    private Long version = 0L;
}
