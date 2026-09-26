package com.irrigaiq.identity.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.irrigaiq.identity.StrongPassword;

/** Request bodies for the authentication and account endpoints. */
public final class AuthRequests {

	private AuthRequests() {
	}

	public record RegisterRequest(@NotBlank @Email @Size(max = 320) String email, @StrongPassword String password,
			@NotBlank @Size(max = 200) String fullName) {
	}

	/** No policy check on sign-in: existing passwords may predate the current policy. */
	public record LoginRequest(@NotBlank @Email @Size(max = 320) String email,
			@NotBlank @Size(max = 256) String password) {
	}

	/** Mobile clients send the refresh token in the body; web clients rely on the HttpOnly cookie. */
	public record RefreshRequest(@Size(max = 512) String refreshToken) {
	}

	public record UpdateProfileRequest(@NotBlank @Size(max = 200) String fullName) {
	}

	public record ChangePasswordRequest(@NotBlank @Size(max = 256) String currentPassword,
			@StrongPassword String newPassword) {
	}

}
