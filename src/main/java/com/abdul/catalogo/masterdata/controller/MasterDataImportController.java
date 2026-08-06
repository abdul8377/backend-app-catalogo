package com.abdul.catalogo.masterdata.controller;

import com.abdul.catalogo.masterdata.service.MasterDataImportService;
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

import java.nio.charset.StandardCharsets;

@Controller
@RequestMapping("/admin/masters/import")
public class MasterDataImportController {
    private final MasterDataImportService service;

    public MasterDataImportController(MasterDataImportService service) {
        this.service = service;
    }

    @GetMapping
    public String page(@RequestParam(required = false) String importId, Model model) {
        if (importId != null && !importId.isBlank()) model.addAttribute("preview", service.get(importId));
        return "masters/import";
    }

    @PostMapping("/preview")
    public String preview(@RequestParam("file") MultipartFile file, Authentication authentication,
                          RedirectAttributes redirect) {
        var preview = service.preview(file, authentication == null ? "admin" : authentication.getName());
        redirect.addFlashAttribute("message", "Vista previa generada. Revisa los errores antes de confirmar.");
        return "redirect:/admin/masters/import?importId=" + preview.id();
    }

    @PostMapping("/{importId}/confirm")
    public String confirm(@PathVariable String importId, RedirectAttributes redirect) {
        service.confirm(importId);
        redirect.addFlashAttribute("message", "Datos maestros confirmados y publicados para sincronización.");
        return "redirect:/admin/masters/import?importId=" + importId;
    }

    @GetMapping("/template")
    public ResponseEntity<byte[]> template() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("plantilla-datos-maestros-v1.xlsx", StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers).body(service.template());
    }
}
