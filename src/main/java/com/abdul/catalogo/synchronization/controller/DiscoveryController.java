package com.abdul.catalogo.synchronization.controller;

import com.abdul.catalogo.synchronization.dto.DiscoveryResponse;
import com.abdul.catalogo.synchronization.service.ServerIdentityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/discovery")
public class DiscoveryController {
    private final ServerIdentityService identityService;

    public DiscoveryController(ServerIdentityService identityService) {
        this.identityService = identityService;
    }

    @GetMapping
    public DiscoveryResponse discovery() {
        return identityService.discovery();
    }
}
