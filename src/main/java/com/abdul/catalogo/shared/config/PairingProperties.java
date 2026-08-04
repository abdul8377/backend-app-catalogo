package com.abdul.catalogo.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("app.pairing")
public record PairingProperties(Duration codeDuration) {
}
