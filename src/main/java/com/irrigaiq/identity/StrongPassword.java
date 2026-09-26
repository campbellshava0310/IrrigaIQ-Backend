package com.irrigaiq.identity;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Password policy, following NIST SP 800-63B: favour length over composition rules. The upper
 * bound is 72 UTF-8 bytes because bcrypt ignores anything beyond that.
 */
@Documented
@Constraint(validatedBy = StrongPasswordValidator.class)
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
public @interface StrongPassword {

	String message() default "Password must be 12-72 characters and not only whitespace";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

}
