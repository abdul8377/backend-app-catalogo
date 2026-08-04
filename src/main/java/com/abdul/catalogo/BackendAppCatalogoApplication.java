package com.abdul.catalogo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BackendAppCatalogoApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackendAppCatalogoApplication.class, args);
    }
}
