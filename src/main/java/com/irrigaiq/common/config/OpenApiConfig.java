package com.irrigaiq.common.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;

import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata. The spec (/v3/api-docs) is the contract the web and mobile clients are built
 * against; it is only served when springdoc is enabled (dev profile).
 */
@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(
		info = @Info(title = "IrrigaIQ API", version = "v1",
				description = "Farm water-intelligence platform: farms, fields, crops, telemetry and irrigation intelligence."),
		security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
@SecurityScheme(name = OpenApiConfig.BEARER_AUTH, type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class OpenApiConfig {

	public static final String BEARER_AUTH = "bearerAuth";

}
