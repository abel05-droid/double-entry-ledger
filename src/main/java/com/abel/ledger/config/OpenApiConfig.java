package com.abel.ledger.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI ledgerOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Double-Entry Ledger API")
                        .description("Production-grade double-entry bookkeeping service: post balanced, "
                                + "idempotent transactions and derive account balances and ledger history.")
                        .version("v1"));
    }
}
