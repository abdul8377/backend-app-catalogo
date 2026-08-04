package com.abdul.catalogo.synchronization.controller;

import com.abdul.catalogo.shared.exception.BusinessRuleException;
import com.abdul.catalogo.synchronization.dto.DeviceRegistrationRequest;
import com.abdul.catalogo.synchronization.dto.DeviceRegistrationResponse;
import com.abdul.catalogo.synchronization.dto.DeviceStatusResponse;
import com.abdul.catalogo.synchronization.security.DevicePrincipal;
import com.abdul.catalogo.synchronization.service.DeviceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/devices")
public class DeviceController {

    private final DeviceService deviceService;

    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public DeviceRegistrationResponse register(@Valid @RequestBody DeviceRegistrationRequest request) {
        return deviceService.register(request);
    }

    @GetMapping("/{id}/status")
    public DeviceStatusResponse status(@PathVariable String id, @AuthenticationPrincipal DevicePrincipal principal) {
        if (!principal.deviceId().equals(id)) {
            throw new BusinessRuleException("DEVICE_ID_MISMATCH", "El token no pertenece al dispositivo solicitado.");
        }
        return deviceService.status(id);
    }
}
