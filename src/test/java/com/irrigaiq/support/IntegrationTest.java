package com.irrigaiq.support;

import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack test base: real security filter chain, real PostgreSQL. The Spring context (and
 * container) is shared by every subclass, so tests create their own uniquely named users.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestcontainer.class)
public abstract class IntegrationTest {

	protected static final String ADMIN_EMAIL = "admin@irrigaiq.test";

	protected static final String ADMIN_PASSWORD = "Test-Admin-Password-1";

	protected static final String PASSWORD = "Correct-Horse-Battery-9";

	protected static final String REFRESH_COOKIE = "irrigaiq_rt";

	@Autowired
	protected MockMvc mvc;

	protected static String uniqueEmail(String prefix) {
		return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
	}

	protected static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
		return request.contentType(MediaType.APPLICATION_JSON).content(body);
	}

	protected static MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder request, String accessToken) {
		return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
	}

	protected static MockHttpServletRequestBuilder web(MockHttpServletRequestBuilder request) {
		return request.header("X-Client-Type", "web");
	}

	protected static Cookie refreshCookie(String value) {
		return new Cookie(REFRESH_COOKIE, value);
	}

	/** Registers a farm manager through the public API (mobile-style, tokens in body). */
	protected Tokens register(String email) throws Exception {
		MvcResult result = mvc
			.perform(json(post("/api/v1/auth/register"),
					"""
							{"email":"%s","password":"%s","fullName":"Test User"}""".formatted(email, PASSWORD)))
			.andExpect(status().isCreated())
			.andReturn();
		return Tokens.from(result);
	}

	protected Tokens login(String email, String password) throws Exception {
		MvcResult result = mvc
			.perform(json(post("/api/v1/auth/login"), """
					{"email":"%s","password":"%s"}""".formatted(email, password)))
			.andExpect(status().isOk())
			.andReturn();
		return Tokens.from(result);
	}

	protected Tokens loginAdmin() throws Exception {
		return login(ADMIN_EMAIL, ADMIN_PASSWORD);
	}

	protected static String read(MvcResult result, String path) throws Exception {
		return JsonPath.read(result.getResponse().getContentAsString(), path).toString();
	}

	public record Tokens(String accessToken, String refreshToken, String userId) {

		static Tokens from(MvcResult result) throws Exception {
			String body = result.getResponse().getContentAsString();
			return new Tokens(JsonPath.read(body, "$.accessToken"), JsonPath.read(body, "$.refreshToken"),
					JsonPath.read(body, "$.user.id"));
		}

	}

}
