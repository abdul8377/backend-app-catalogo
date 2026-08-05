package com.abdul.catalogo.product.controller;

import com.abdul.catalogo.product.dto.ProductResponse;
import com.abdul.catalogo.product.dto.ProductUpsertRequest;
import com.abdul.catalogo.product.model.ProductStatus;
import com.abdul.catalogo.product.model.ProductType;
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

@Controller
@RequestMapping("/admin/products")
public class ProductWebController {

    private static final String SERVER_WEB = "server-web";
    private final ProductService productService;
    private final ProductFormMapper formMapper;

    public ProductWebController(ProductService productService, ProductFormMapper formMapper) {
        this.productService = productService;
        this.formMapper = formMapper;
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
        model.addAttribute("productForm", new ProductForm());
        model.addAttribute("editing", false);
        model.addAttribute("statuses", ProductStatus.values());
        model.addAttribute("productTypes", ProductType.values());
        return "products/form";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute ProductForm productForm, BindingResult binding, Model model,
                         RedirectAttributes redirect) {
        if (binding.hasErrors()) return formWithMetadata(model, false);
        ProductResponse created;
        try {
            created = productService.create(toRequest(productForm), SERVER_WEB);
        } catch (BusinessRuleException exception) {
            binding.reject(exception.getCode(), exception.getMessage());
            return formWithMetadata(model, false);
        }
        redirect.addFlashAttribute("message", "Producto " + created.code() + " creado y registrado para sincronización.");
        return "redirect:/admin/products";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable String id, Model model) {
        ProductResponse product = productService.get(id);
        ProductForm form = new ProductForm();
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
        form.setAttributesJson(formMapper.pretty(product.aggregate(), "attributes", true));
        form.setVariantsJson(formMapper.pretty(product.aggregate(), "variants", false));
        form.setPresentationsJson(formMapper.pretty(product.aggregate(), "presentations", false));
        form.setPricesJson(formMapper.pretty(product.aggregate(), "prices", false));
        form.setImagesJson(formMapper.pretty(product.aggregate(), "images", false));
        model.addAttribute("productForm", form);
        model.addAttribute("productId", id);
        model.addAttribute("editing", true);
        model.addAttribute("statuses", ProductStatus.values());
        model.addAttribute("productTypes", ProductType.values());
        return "products/form";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable String id, @Valid @ModelAttribute ProductForm productForm,
                         BindingResult binding, Model model, RedirectAttributes redirect) {
        if (binding.hasErrors()) {
            model.addAttribute("productId", id);
            return formWithMetadata(model, true);
        }
        try {
            productService.update(id, toRequest(productForm), SERVER_WEB);
        } catch (BusinessRuleException exception) {
            binding.reject(exception.getCode(), exception.getMessage());
            model.addAttribute("productId", id);
            return formWithMetadata(model, true);
        }
        redirect.addFlashAttribute("message", "Producto actualizado. El cambio está disponible para la tablet.");
        return "redirect:/admin/products";
    }

    @PostMapping("/{id}/deactivate")
    public String deactivate(@PathVariable String id, RedirectAttributes redirect) {
        productService.deactivate(id, SERVER_WEB);
        redirect.addFlashAttribute("message", "Producto desactivado y registrado para sincronización.");
        return "redirect:/admin/products";
    }

    private String formWithMetadata(Model model, boolean editing) {
        model.addAttribute("editing", editing);
        model.addAttribute("statuses", ProductStatus.values());
        model.addAttribute("productTypes", ProductType.values());
        return "products/form";
    }

    private ProductUpsertRequest toRequest(ProductForm form) {
        return new ProductUpsertRequest(form.getCode(), form.getName(), form.getDescription(), form.getCompany(),
                form.getCompanyId(), form.getBrand(), form.getBrandId(), form.getCategory(), form.getCategoryId(),
                form.getSubcategory(), form.getSubcategoryId(), form.getProductType(), form.getStatus(), form.getVersion(),
                formMapper.details(form));
    }
}
