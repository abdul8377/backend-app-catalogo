package com.abdul.catalogo.synchronization.controller;

import com.abdul.catalogo.synchronization.service.DeviceService;
import com.abdul.catalogo.synchronization.service.ServerIdentityService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminDeviceWebController {
    private final DeviceService deviceService;
    private final ServerIdentityService identityService;

    public AdminDeviceWebController(DeviceService deviceService, ServerIdentityService identityService) {
        this.deviceService = deviceService;
        this.identityService = identityService;
    }

    @GetMapping("/admin/devices")
    public String devices(Model model) {
        model.addAttribute("devices", deviceService.list());
        model.addAttribute("server", identityService.discovery());
        return "devices/list";
    }
}
