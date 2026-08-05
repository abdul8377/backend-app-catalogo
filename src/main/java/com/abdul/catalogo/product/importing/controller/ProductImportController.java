package com.abdul.catalogo.product.importing.controller;

import com.abdul.catalogo.product.importing.dto.ProductImportPreviewResponse;
import com.abdul.catalogo.product.importing.service.ProductImportService;
import com.abdul.catalogo.shared.exception.BusinessRuleException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/products/import")
public class ProductImportController {
    private static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private final ProductImportService importService;

    public ProductImportController(ProductImportService importService) { this.importService = importService; }

    @GetMapping
    public String page(@RequestParam(required = false) String id, Model model) {
        if (id != null && !id.isBlank()) model.addAttribute("preview", importService.get(id));
        return "products/import";
    }

    @GetMapping("/template")
    public ResponseEntity<byte[]> template() {
        return download(importService.template(), "plantilla-productos-v2.xlsx");
    }

    @PostMapping("/preview")
    public String preview(@RequestParam("file") MultipartFile file,
                          @RequestParam(name = "images", required = false) MultipartFile images,
                          Authentication authentication, Model model) {
        try {
            ProductImportPreviewResponse preview = importService.preview(file, images, authentication.getName());
            model.addAttribute("preview", preview);
        } catch (BusinessRuleException exception) {
            model.addAttribute("error", exception.getMessage());
        }
        return "products/import";
    }

    @PostMapping("/{id}/confirm")
    public String confirm(@PathVariable String id, RedirectAttributes redirect) {
        try {
            ProductImportPreviewResponse result = importService.confirm(id);
            redirect.addFlashAttribute("message", result.status().name().equals("CONFIRMED")
                    ? "Importación confirmada. Los productos e imágenes quedaron disponibles para sincronización."
                    : "La importación terminó con filas fallidas. Descarga el informe.");
        } catch (BusinessRuleException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/admin/products/import?id=" + id;
    }

    @GetMapping("/{id}/report")
    public ResponseEntity<byte[]> report(@PathVariable String id) {
        return download(importService.report(id), "informe-importacion-" + id + ".xlsx");
    }

    private ResponseEntity<byte[]> download(byte[] bytes, String name) {
        return ResponseEntity.ok().contentType(XLSX).contentLength(bytes.length)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .body(bytes);
    }
}
