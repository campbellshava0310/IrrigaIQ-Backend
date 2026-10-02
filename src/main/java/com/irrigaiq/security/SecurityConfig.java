package com.irrigaiq.security;

import java.time.Duration;
import java.util.List;

import com.irrigaiq.common.web.RequestIdFilter;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless API security. Every request authenticates with a bearer access token except the
 * explicitly public auth, health and documentation endpoints, and device ingestion (which
 * authenticates with a per-device key instead).
 *
 * <p>CSRF protection is disabled because browsers cannot attach bearer tokens automatically. The
 * only cookie-authenticated endpoints (refresh/logout) are guarded by a {@code SameSite=Strict},
 * path-scoped cookie <em>and</em> a required {@code X-Client-Type} header, which cross-site forms
 * cannot send and cross-origin scripts cannot send without passing CORS.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

	public static final String CLIENT_TYPE_HEADER = "X-Client-Type";

	@Bean
	public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, SecurityErrorHandler errorHandler)
			throws Exception {
		http.csrf(AbstractHttpConfigurer::disable)
			.cors(Customizer.withDefaults())
			.httpBasic(AbstractHttpConfigurer::disable)
			.formLogin(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.requestCache(AbstractHttpConfigurer::disable)
			.sessionManagement((session) -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.headers((headers) -> headers.referrerPolicy((referrer) -> referrer.policy(ReferrerPolicy.NO_REFERRER)))
			.authorizeHttpRequests((requests) -> requests
				.requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login",
						"/api/v1/auth/refresh", "/api/v1/auth/logout")
				.permitAll()
				.requestMatchers(HttpMethod.GET, "/.well-known/jwks.json")
				.permitAll()
				// Devices authenticate with their own key, checked by the telemetry module.
				.requestMatchers(HttpMethod.POST, "/api/v1/telemetry")
				.permitAll()
				.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info")
				.permitAll()
				.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
				.permitAll()
				.requestMatchers("/error")
				.permitAll()
				.requestMatchers("/api/v1/admin/**", "/actuator/**")
				.hasRole(Role.ADMIN.name())
				.anyRequest()
				.authenticated())
			.oauth2ResourceServer((resourceServer) -> resourceServer
				.jwt((jwt) -> jwt.jwtAuthenticationConverter(new AuthenticatedUserConverter()))
				.authenticationEntryPoint(errorHandler)
				.accessDeniedHandler(errorHandler))
			.exceptionHandling((exceptions) -> exceptions.authenticationEntryPoint(errorHandler)
				.accessDeniedHandler(errorHandler));
		return http.build();
	}

	/**
	 * Delegating encoder: bcrypt today, with the algorithm id stored in each hash so the default can
	 * be upgraded later and existing hashes re-encoded transparently at next login.
	 */
	@Bean
	public PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}

	@Bean
	public CorsConfigurationSource corsConfigurationSource(SecurityProperties properties) {
		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOrigins(properties.cors().allowedOrigins());
		configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		configuration.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE,
				HttpHeaders.ACCEPT, CLIENT_TYPE_HEADER, RequestIdFilter.HEADER));
		configuration.setExposedHeaders(List.of(RequestIdFilter.HEADER, HttpHeaders.WWW_AUTHENTICATE));
		configuration.setAllowCredentials(true);
		configuration.setMaxAge(Duration.ofHours(1));
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", configuration);
		return source;
	}

}
