package com.irrigaiq.identity;

import java.nio.charset.StandardCharsets;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class StrongPasswordValidator implements ConstraintValidator<StrongPassword, String> {

	public static final int MIN_LENGTH = 12;

	public static final int MAX_BYTES = 72;

	@Override
	public boolean isValid(String password, ConstraintValidatorContext context) {
		if (password == null) {
			return false;
		}
		return password.codePointCount(0, password.length()) >= MIN_LENGTH && !password.isBlank()
				&& password.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
	}

}
