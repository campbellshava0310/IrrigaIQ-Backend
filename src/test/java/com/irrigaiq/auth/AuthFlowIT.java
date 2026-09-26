package com.irrigaiq.auth;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;

import com.irrigaiq.support.IntegrationTest;

import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthFlowIT extends IntegrationTest {

	@Test
	void registrationSignsInAsFarmManager() throws Exception {
		String email = uniqueEmail("register");
		Tokens tokens = register(email.toUpperCase());

		mvc.perform(bearer(get("/api/v1/me"), tokens.accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.email").value(email))
			.andExpect(jsonPath("$.roles").value(hasItem("FARM_MANAGER")))
			.andExpect(jsonPath("$.roles").value(not(hasItem("ADMIN"))));
	}

	@Test
	void duplicateRegistrationIsRejected() throws Exception {
		String email = uniqueEmail("dup");
		register(email);
		mvc.perform(json(post("/api/v1/auth/register"), """
				{"email":"%s","password":"%s","fullName":"Again"}""".formatted(email, PASSWORD)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
	}

	@Test
	void weakPasswordsAndMalformedInputAreRejectedWithFieldErrors() throws Exception {
		mvc.perform(json(post("/api/v1/auth/register"), """
				{"email":"not-an-email","password":"short","fullName":" "}"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors", hasSize(3)))
			.andExpect(jsonPath("$.requestId").exists());
	}

	@Test
	void wrongPasswordAndUnknownEmailAreIndistinguishable() throws Exception {
		String email = uniqueEmail("enum");
		register(email);
		mvc.perform(json(post("/api/v1/auth/login"), """
				{"email":"%s","password":"Wrong-Password-123"}""".formatted(email)))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
		mvc.perform(json(post("/api/v1/auth/login"), """
				{"email":"%s","password":"Wrong-Password-123"}""".formatted(uniqueEmail("ghost"))))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
	}

	@Test
	void protectedEndpointsRequireAValidAccessToken() throws Exception {
		mvc.perform(get("/api/v1/me"))
			.andExpect(status().isUnauthorized())
			.andExpect(header().exists("WWW-Authenticate"))
			.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
		mvc.perform(bearer(get("/api/v1/me"), "not.a.jwt"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
	}

	@Test
	void refreshRotatesTokenAndReplayRevokesTheWholeSession() throws Exception {
		Tokens first = register(uniqueEmail("rotate"));

		MvcResult rotated = mvc.perform(json(post("/api/v1/auth/refresh"), """
				{"refreshToken":"%s"}""".formatted(first.refreshToken()))).andExpect(status().isOk()).andReturn();
		String second = read(rotated, "$.refreshToken");
		assertThat(second).isNotEqualTo(first.refreshToken());

		// Replaying the rotated token looks like theft: it fails and takes the live token with it.
		mvc.perform(json(post("/api/v1/auth/refresh"), """
				{"refreshToken":"%s"}""".formatted(first.refreshToken())))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));
		mvc.perform(json(post("/api/v1/auth/refresh"), """
				{"refreshToken":"%s"}""".formatted(second))).andExpect(status().isUnauthorized());
	}

	@Test
	void webClientsGetAnHttpOnlyCookieInsteadOfABodyToken() throws Exception {
		String email = uniqueEmail("web");
		register(email);

		MvcResult login = mvc.perform(web(json(post("/api/v1/auth/login"), """
				{"email":"%s","password":"%s"}""".formatted(email, PASSWORD))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.refreshToken").doesNotExist())
			.andExpect(jsonPath("$.accessToken").exists())
			.andReturn();
		Cookie cookie = login.getResponse().getCookie(REFRESH_COOKIE);
		assertThat(cookie).isNotNull();
		assertThat(cookie.isHttpOnly()).isTrue();
		assertThat(cookie.getPath()).isEqualTo("/api/v1/auth");
		assertThat(login.getResponse().getHeader("Set-Cookie")).contains("SameSite=Strict");

		// Without the client-type header the cookie is ignored (CSRF defence).
		mvc.perform(post("/api/v1/auth/refresh").cookie(cookie)).andExpect(status().isUnauthorized());

		MvcResult refreshed = mvc.perform(web(post("/api/v1/auth/refresh")).cookie(cookie))
			.andExpect(status().isOk())
			.andReturn();
		Cookie rotated = refreshed.getResponse().getCookie(REFRESH_COOKIE);
		assertThat(rotated.getValue()).isNotEqualTo(cookie.getValue());

		MvcResult logout = mvc.perform(web(post("/api/v1/auth/logout")).cookie(rotated))
			.andExpect(status().isNoContent())
			.andReturn();
		assertThat(logout.getResponse().getCookie(REFRESH_COOKIE).getMaxAge()).isZero();
		mvc.perform(web(post("/api/v1/auth/refresh")).cookie(rotated)).andExpect(status().isUnauthorized());
	}

	@Test
	void repeatedFailuresLockTheAccountEvenForTheCorrectPassword() throws Exception {
		String email = uniqueEmail("lockout");
		register(email);
		for (int attempt = 0; attempt < 3; attempt++) { // max-failed-attempts = 3 in the test profile
			mvc.perform(json(post("/api/v1/auth/login"), """
					{"email":"%s","password":"Wrong-Password-123"}""".formatted(email)))
				.andExpect(status().isUnauthorized());
		}
		mvc.perform(json(post("/api/v1/auth/login"), """
				{"email":"%s","password":"%s"}""".formatted(email, PASSWORD)))
			.andExpect(status().isLocked())
			.andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"))
			.andExpect(jsonPath("$.lockedUntil").exists());
	}

	@Test
	void changingPasswordSignsOutOtherDevicesButKeepsThisOne() throws Exception {
		String email = uniqueEmail("password");
		Tokens phone = register(email);
		Tokens laptop = login(email, PASSWORD);

		mvc.perform(bearer(get("/api/v1/me/sessions"), laptop.accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(2)))
			.andExpect(jsonPath("$[?(@.current == true)]", hasSize(1)));

		mvc.perform(bearer(json(post("/api/v1/me/password"), """
				{"currentPassword":"Wrong-Password-123","newPassword":"Another-Strong-Pass-7"}"""), laptop.accessToken()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("CURRENT_PASSWORD_INCORRECT"));

		mvc.perform(bearer(json(post("/api/v1/me/password"), """
				{"currentPassword":"%s","newPassword":"Another-Strong-Pass-7"}""".formatted(PASSWORD)),
				laptop.accessToken()))
			.andExpect(status().isNoContent());

		mvc.perform(json(post("/api/v1/auth/refresh"), """
				{"refreshToken":"%s"}""".formatted(phone.refreshToken()))).andExpect(status().isUnauthorized());
		mvc.perform(json(post("/api/v1/auth/refresh"), """
				{"refreshToken":"%s"}""".formatted(laptop.refreshToken()))).andExpect(status().isOk());
		login(email, "Another-Strong-Pass-7");
	}

	@Test
	void usersCanSignOutASpecificDevice() throws Exception {
		String email = uniqueEmail("devices");
		Tokens phone = register(email);
		Tokens laptop = login(email, PASSWORD);
		MvcResult sessions = mvc.perform(bearer(get("/api/v1/me/sessions"), laptop.accessToken()))
			.andExpect(status().isOk())
			.andReturn();
		String phoneSession = read(sessions, "$[?(@.current == false)].id").replaceAll("[\\[\\]\"]", "");

		mvc.perform(bearer(delete("/api/v1/me/sessions/" + phoneSession), laptop.accessToken()))
			.andExpect(status().isNoContent());
		mvc.perform(json(post("/api/v1/auth/refresh"), """
				{"refreshToken":"%s"}""".formatted(phone.refreshToken()))).andExpect(status().isUnauthorized());
	}

	@Test
	void jwksPublishesOnlyThePublicKey() throws Exception {
		mvc.perform(get("/.well-known/jwks.json"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.keys[0].kty").value("RSA"))
			.andExpect(jsonPath("$.keys[0].n").exists())
			.andExpect(jsonPath("$.keys[0].d").doesNotExist());
	}

}
