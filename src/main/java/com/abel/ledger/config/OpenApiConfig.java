package com.abel.ledger.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME_NAME = "bearerAuth";

    @Bean
    public OpenAPI ledgerOpenApi() {
        SecurityScheme bearerScheme = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("Obtain a token from POST /api/v1/auth/login, then authorize with it here.");

        return new OpenAPI()
                .info(new Info()
                        .title("Double-Entry Ledger API")
                        .description("Production-grade double-entry bookkeeping service: post balanced, "
                                + "idempotent transactions and derive account balances and ledger history. "
                                + "Posting transactions and account creation require an ADMIN-role JWT; "
                                + "read endpoints require any authenticated user.")
                        .version("v1"))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME_NAME, bearerScheme))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME_NAME));
    }
}
