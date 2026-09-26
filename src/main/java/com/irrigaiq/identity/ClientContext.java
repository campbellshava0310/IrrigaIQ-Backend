package com.irrigaiq.identity;

import jakarta.servlet.http.HttpServletRequest;

import com.irrigaiq.common.web.RequestMetadata;
import com.irrigaiq.security.SecurityConfig;

/** Who is calling the auth endpoints, recorded against each session. */
public record ClientContext(ClientType clientType, String ipAddress, String userAgent) {

	public static ClientContext from(HttpServletRequest request) {
		RequestMetadata metadata = RequestMetadata.from(request);
		return new ClientContext(ClientType.fromHeader(request.getHeader(SecurityConfig.CLIENT_TYPE_HEADER)),
				metadata.ipAddress(), metadata.userAgent());
	}

}
