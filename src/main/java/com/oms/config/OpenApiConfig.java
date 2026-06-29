package com.oms.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Swagger UI at /swagger-ui/index.html, raw spec at /v3/api-docs — see SPEC.md §6 for the design behind these endpoints. */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI omsOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Order Management System API")
                .description("Core order model, configurable workflow engine, and human task queue. "
                        + "See SPEC.md (design) and GUIDE.md (how-to) in the repo root.")
                .version("v0.1"));
    }
}
