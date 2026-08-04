package com.abdul.catalogo.synchronization.controller;

import com.abdul.catalogo.synchronization.dto.SyncBootstrapResponse;
import com.abdul.catalogo.synchronization.dto.SyncPullResponse;
import com.abdul.catalogo.synchronization.dto.SyncPushRequest;
import com.abdul.catalogo.synchronization.dto.SyncPushResponse;
import com.abdul.catalogo.synchronization.dto.SyncStatusResponse;
import com.abdul.catalogo.synchronization.security.DevicePrincipal;
import com.abdul.catalogo.synchronization.service.SyncPushService;
import com.abdul.catalogo.synchronization.service.SyncReadService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sync")
public class SyncController {

    private final SyncPushService pushService;
    private final SyncReadService readService;

    public SyncController(SyncPushService pushService, SyncReadService readService) {
        this.pushService = pushService;
        this.readService = readService;
    }

    @PostMapping("/push")
    public SyncPushResponse push(@AuthenticationPrincipal DevicePrincipal principal,
                                 @Valid @RequestBody SyncPushRequest request) {
        return pushService.push(principal.deviceId(), request);
    }

    @GetMapping("/pull")
    public SyncPullResponse pull(@AuthenticationPrincipal DevicePrincipal principal,
                                 @RequestParam(defaultValue = "0") long after,
                                 @RequestParam(defaultValue = "300") int limit) {
        return readService.pull(principal.deviceId(), after, limit);
    }

    @GetMapping("/bootstrap")
    public SyncBootstrapResponse bootstrap(@RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "300") int limit) {
        return readService.bootstrap(page, limit);
    }

    @GetMapping("/status")
    public SyncStatusResponse status() {
        return readService.status();
    }
}
