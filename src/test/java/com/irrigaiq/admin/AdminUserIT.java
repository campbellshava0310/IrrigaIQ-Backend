package com.irrigaiq.admin;

import org.junit.jupiter.api.Test;

import com.irrigaiq.support.IntegrationTest;

import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminUserIT extends IntegrationTest {

	@Test
	void farmManagersCannotReachTheAdminApi() throws Exception {
		Tokens farmer = register(uniqueEmail("farmer"));
		for (String path : new String[] { "/api/v1/admin/users", "/api/v1/admin/overview",
				"/api/v1/admin/audit-events", "/actuator/metrics" }) {
			mvc.perform(bearer(get(path), farmer.accessToken()))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));
		}
	}

	@Test
	void adminCanCreateSearchAndPromoteUsers() throws Exception {
		Tokens admin = loginAdmin();
		String email = uniqueEmail("created");

		MvcResult created = mvc.perform(bearer(json(post("/api/v1/admin/users"), """
				{"email":"%s","fullName":"Field Agronomist","temporaryPassword":"%s","roles":["FARM_MANAGER"]}"""
			.formatted(email, PASSWORD)), admin.accessToken()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.email").value(email))
			.andReturn();
		String userId = read(created, "$.id");

		mvc.perform(bearer(get("/api/v1/admin/users").param("q", email.substring(0, 12)), admin.accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(1));

		mvc.perform(bearer(json(patch("/api/v1/admin/users/" + userId), """
				{"roles":["FARM_MANAGER","ADMIN"]}"""), admin.accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.roles.length()").value(2));

		mvc.perform(bearer(get("/api/v1/admin/audit-events").param("action", "USER_UPDATED")
			.param("targetId", userId), admin.accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(1))
			.andExpect(jsonPath("$.content[0].details.roles.to.length()").value(2));

		Tokens promoted = login(email, PASSWORD);
		mvc.perform(bearer(get("/api/v1/admin/overview"), promoted.accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.users.admins").value(greaterThanOrEqualTo(2)));
	}

	@Test
	void adminsCannotRemoveTheirOwnAdminAccess() throws Exception {
		Tokens admin = loginAdmin();
		mvc.perform(bearer(json(patch("/api/v1/admin/users/" + admin.userId()), """
				{"roles":["FARM_MANAGER"]}"""), admin.accessToken()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("OPERATION_NOT_ALLOWED"));
		mvc.perform(bearer(json(patch("/api/v1/admin/users/" + admin.userId()), """
				{"enabled":false}"""), admin.accessToken())).andExpect(status().isConflict());
	}

	@Test
	void disablingAUserEndsTheirSessionsAndBlocksSignIn() throws Exception {
		Tokens admin = loginAdmin();
		String email = uniqueEmail("disabled");
		Tokens user = register(email);

		mvc.perform(bearer(json(patch("/api/v1/admin/users/" + user.userId()), """
				{"enabled":false}"""), admin.accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.enabled").value(false));

		mvc.perform(json(post("/api/v1/auth/refresh"), """
				{"refreshToken":"%s"}""".formatted(user.refreshToken()))).andExpect(status().isUnauthorized());
		mvc.perform(json(post("/api/v1/auth/login"), """
				{"email":"%s","password":"%s"}""".formatted(email, PASSWORD)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
	}

	@Test
	void adminCanUnlockALockedAccount() throws Exception {
		Tokens admin = loginAdmin();
		String email = uniqueEmail("unlock");
		Tokens user = register(email);
		for (int attempt = 0; attempt < 3; attempt++) {
			mvc.perform(json(post("/api/v1/auth/login"), """
					{"email":"%s","password":"Wrong-Password-123"}""".formatted(email)));
		}
		mvc.perform(bearer(get("/api/v1/admin/users/" + user.userId()), admin.accessToken()))
			.andExpect(jsonPath("$.locked").value(true));

		mvc.perform(bearer(post("/api/v1/admin/users/" + user.userId() + "/unlock"), admin.accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.locked").value(false));
		login(email, PASSWORD);
	}

}
