package com.abdul.catalogo.product.controller;

import com.abdul.catalogo.product.dto.ProductResponse;
import com.abdul.catalogo.product.dto.ProductUpsertRequest;
import com.abdul.catalogo.product.model.ProductStatus;
import com.abdul.catalogo.product.service.ProductService;
import com.abdul.catalogo.product.web.ProductForm;
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

    public ProductWebController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    public String list(@RequestParam(defaultValue = "") String query,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("products", productService.list(query, page, 50));
        model.addAttribute("query", query);
        return "products/list";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        model.addAttribute("productForm", new ProductForm());
        model.addAttribute("editing", false);
        model.addAttribute("statuses", ProductStatus.values());
        return "products/form";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute ProductForm productForm, BindingResult binding, Model model,
                         RedirectAttributes redirect) {
        if (binding.hasErrors()) return formWithMetadata(model, false);
        ProductResponse created = productService.create(toRequest(productForm), SERVER_WEB);
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
        form.setBrand(product.brand());
        form.setCategory(product.category());
        form.setStatus(product.status());
        form.setVersion(product.version());
        model.addAttribute("productForm", form);
        model.addAttribute("productId", id);
        model.addAttribute("editing", true);
        model.addAttribute("statuses", ProductStatus.values());
        return "products/form";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable String id, @Valid @ModelAttribute ProductForm productForm,
                         BindingResult binding, Model model, RedirectAttributes redirect) {
        if (binding.hasErrors()) {
            model.addAttribute("productId", id);
            return formWithMetadata(model, true);
        }
        productService.update(id, toRequest(productForm), SERVER_WEB);
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
        return "products/form";
    }

    private ProductUpsertRequest toRequest(ProductForm form) {
        return new ProductUpsertRequest(form.getCode(), form.getName(), form.getDescription(), form.getCompany(),
                form.getBrand(), form.getCategory(), form.getStatus(), form.getVersion(), null);
    }
}
