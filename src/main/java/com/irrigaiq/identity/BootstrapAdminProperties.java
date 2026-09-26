package com.irrigaiq.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * First administrator, created at startup only while no administrator exists. Leave blank in
 * environments where admins are provisioned another way.
 */
@ConfigurationProperties("irrigaiq.bootstrap-admin")
public record BootstrapAdminProperties(String email, String password,
		@DefaultValue("Platform Administrator") String fullName) {
}
