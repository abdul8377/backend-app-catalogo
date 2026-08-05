package com.abdul.catalogo.product.controller;

import com.abdul.catalogo.product.dto.ProductResponse;
import com.abdul.catalogo.product.model.ProductStatus;
import com.abdul.catalogo.product.model.ProductType;
import com.abdul.catalogo.product.service.ProductFormImageService;
import com.abdul.catalogo.product.service.ProductFormMapper;
import com.abdul.catalogo.product.service.ProductService;
import com.abdul.catalogo.product.web.ProductCardView;
import com.abdul.catalogo.product.web.ProductForm;
import com.abdul.catalogo.shared.exception.BusinessRuleException;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Arrays;
import java.util.UUID;

@Controller
@RequestMapping("/admin/products")
public class ProductWebController {

    private static final String SERVER_WEB = "server-web";
    private final ProductService productService;
    private final ProductFormMapper formMapper;
    private final ProductFormImageService imageService;

    public ProductWebController(ProductService productService, ProductFormMapper formMapper,
                                ProductFormImageService imageService) {
        this.productService = productService;
        this.formMapper = formMapper;
        this.imageService = imageService;
    }

    @GetMapping
    public String list(@RequestParam(defaultValue = "") String query,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        var products = productService.list(query, page, 24).map(ProductCardView::from);
        model.addAttribute("products", products);
        model.addAttribute("query", query);
        model.addAttribute("totalProducts", products.getTotalElements());
        model.addAttribute("activeProducts", products.getContent().stream()
                .filter(product -> product.status() == ProductStatus.ACTIVE).count());
        model.addAttribute("withoutPrice", products.getContent().stream()
                .filter(product -> !product.priceState().equals("priced")).count());
        return "products/list";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        ProductForm form = new ProductForm();
        form.setProductId(UUID.randomUUID().toString());
        form.setStatus(ProductStatus.DRAFT);
        model.addAttribute("productForm", form);
        return formWithMetadata(model, false);
    }

    @PostMapping
    public String create(@Valid @ModelAttribute ProductForm productForm, BindingResult binding, Model model,
                         RedirectAttributes redirect) {
        if (binding.hasErrors()) return formWithMetadata(model, false);
        try {
            imageService.attachUploads(productForm);
            ProductResponse created = productService.createAggregate(formMapper.aggregate(productForm), SERVER_WEB);
            redirect.addFlashAttribute("message", "Producto " + created.code()
                    + " creado y registrado para sincronización.");
            return "redirect:/admin/products";
        } catch (BusinessRuleException exception) {
            binding.reject(exception.getCode(), exception.getMessage());
            return formWithMetadata(model, false);
        }
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable String id, Model model) {
        ProductResponse product = productService.get(id);
        ProductForm form = new ProductForm();
        form.setProductId(product.id());
        form.setCode(product.code());
        form.setName(product.name());
        form.setDescription(product.description());
        form.setCompany(product.company());
        form.setCompanyId(product.companyId());
        form.setBrand(product.brand());
        form.setBrandId(product.brandId());
        form.setCategory(product.category());
        form.setCategoryId(product.categoryId());
        form.setSubcategory(product.subcategory());
        form.setSubcategoryId(product.subcategoryId());
        form.setProductType(product.productType());
        form.setStatus(product.status());
        form.setVersion(product.version());
        form.setAttributesJson(formMapper.compact(product.aggregate(), "attributes", true));
        form.setVariantsJson(formMapper.compact(product.aggregate(), "variants", false));
        form.setPresentationsJson(formMapper.compact(product.aggregate(), "presentations", false));
        form.setPricesJson(formMapper.compact(product.aggregate(), "prices", false));
        form.setImagesJson(formMapper.compact(product.aggregate(), "images", false));
        form.setSalesConfigurationJson(formMapper.compact(product.aggregate(), "salesConfiguration", true));
        form.setPricingConfigurationJson(formMapper.compact(product.aggregate(), "pricingConfiguration", true));
        form.setImageConfigurationJson(formMapper.compact(product.aggregate(), "imageConfiguration", true));
        form.setFamilyAxesJson(formMapper.compact(product.aggregate(), "familyAxes", false));
        form.setAttributeValuesJson(formMapper.compact(product.aggregate(), "attributeValues", false));
        form.setAttributeOptionsJson(formMapper.compact(product.aggregate(), "attributeOptions", false));
        model.addAttribute("productForm", form);
        model.addAttribute("productId", id);
        return formWithMetadata(model, true);
    }

    @PostMapping("/{id}")
    public String update(@PathVariable String id, @Valid @ModelAttribute ProductForm productForm,
                         BindingResult binding, Model model, RedirectAttributes redirect) {
        productForm.setProductId(id);
        if (binding.hasErrors()) {
            model.addAttribute("productId", id);
            return formWithMetadata(model, true);
        }
        try {
            imageService.attachUploads(productForm);
            productService.updateAggregate(id, productForm.getVersion(), formMapper.aggregate(productForm), SERVER_WEB);
            redirect.addFlashAttribute("message", "Producto actualizado. El cambio está disponible para la tablet.");
            return "redirect:/admin/products";
        } catch (BusinessRuleException exception) {
            binding.reject(exception.getCode(), exception.getMessage());
            model.addAttribute("productId", id);
            return formWithMetadata(model, true);
        }
    }

    @PostMapping("/{id}/deactivate")
    public String deactivate(@PathVariable String id, RedirectAttributes redirect) {
        productService.deactivate(id, SERVER_WEB);
        redirect.addFlashAttribute("message", "Producto desactivado y registrado para sincronización.");
        return "redirect:/admin/products";
    }

    private String formWithMetadata(Model model, boolean editing) {
        model.addAttribute("editing", editing);
        model.addAttribute("statuses", Arrays.stream(ProductStatus.values())
                .filter(status -> status != ProductStatus.DELETED).toList());
        model.addAttribute("productTypes", ProductType.values());
        return "products/form";
    }
}
