package com.abdul.catalogo.synchronization.controller;

import com.abdul.catalogo.synchronization.model.ConflictResolution;
import com.abdul.catalogo.synchronization.service.SyncConflictService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class SyncConflictWebController {
    private final SyncConflictService service;
    public SyncConflictWebController(SyncConflictService service) { this.service = service; }
    @GetMapping("/admin/conflicts")
    public String list(Model model) { model.addAttribute("conflicts", service.pending()); model.addAttribute("resolutions", ConflictResolution.values()); return "conflicts/list"; }
    @PostMapping("/admin/conflicts/{id}/resolve")
    public String resolve(@PathVariable String id, @RequestParam ConflictResolution resolution,
                          @RequestParam(defaultValue = "") String mergePayload, Authentication authentication,
                          RedirectAttributes redirect) {
        service.resolve(id, resolution, mergePayload, authentication.getName());
        redirect.addFlashAttribute("message", "Conflicto resuelto y auditado."); return "redirect:/admin/conflicts";
    }
}
