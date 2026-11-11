package com.ilkeiapps.skyworx.notulenai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * springdoc OpenAPI configuration. Declares a global HTTP bearer (JWT) security scheme so Swagger UI
 * exposes an "Authorize" button and sends {@code Authorization: Bearer <token>} on every request.
 * The login endpoint stays usable without a token because it is permitted in {@link SecurityConfiguration}.
 */
@Configuration
public class OpenApiConfiguration {

	private static final String SECURITY_SCHEME_NAME = "bearerAuth";

	@Bean
	public OpenAPI apiDefinition() {
		return new OpenAPI()
				.info(new Info().title("Notulen AI API").version("v1"))
				.addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME))
				.components(new Components().addSecuritySchemes(SECURITY_SCHEME_NAME,
						new SecurityScheme()
								.name(SECURITY_SCHEME_NAME)
								.type(SecurityScheme.Type.HTTP)
								.scheme("bearer")
								.bearerFormat("JWT")));
	}
}
