package com.abdul.catalogo.synchronization.controller;

import com.abdul.catalogo.synchronization.dto.DeviceTokenResponse;
import com.abdul.catalogo.synchronization.dto.PairingCodeResponse;
import com.abdul.catalogo.synchronization.service.DeviceService;
import com.abdul.catalogo.synchronization.service.PairingCodeService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin")
public class AdminDeviceController {
    private final PairingCodeService pairingCodeService;
    private final DeviceService deviceService;

    public AdminDeviceController(PairingCodeService pairingCodeService, DeviceService deviceService) {
        this.pairingCodeService = pairingCodeService;
        this.deviceService = deviceService;
    }

    @PostMapping("/pairing-codes")
    public PairingCodeResponse createPairingCode(Authentication authentication) {
        return pairingCodeService.create(authentication.getName());
    }

    @PostMapping("/devices/{id}/revoke")
    public void revoke(@PathVariable String id, Authentication authentication) {
        deviceService.revoke(id, authentication.getName());
    }

    @PostMapping("/devices/{id}/rotate-token")
    public DeviceTokenResponse rotateToken(@PathVariable String id, Authentication authentication) {
        return deviceService.rotateToken(id, authentication.getName());
    }
}
