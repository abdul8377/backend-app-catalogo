package com.abdul.catalogo.shared.ops;

import com.abdul.catalogo.shared.config.ContractProperties;
import com.abdul.catalogo.synchronization.service.ServerIdentityService;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

@Component
public class OperationalInfoContributor implements InfoContributor {
    private final ContractProperties contract;
    private final ServerIdentityService identityService;
    public OperationalInfoContributor(ContractProperties contract, ServerIdentityService identityService) { this.contract = contract; this.identityService = identityService; }
    @Override public void contribute(Info.Builder builder) {
        var server = identityService.discovery();
        builder.withDetail("serverId", server.serverId()).withDetail("serverName", server.serverName())
                .withDetail("apiContractVersion", contract.version()).withDetail("serviceType", server.serviceType());
    }
}
