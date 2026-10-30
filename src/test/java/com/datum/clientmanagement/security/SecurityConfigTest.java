package com.datum.clientmanagement.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Web slice without actuator or controllers: a 404 means the request passed the security filter chain.
 * Controllers are excluded explicitly so the real customer endpoints (and their dependencies) stay out of the slice.
 */
@WebMvcTest(excludeFilters = @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = Controller.class))
@Import(SecurityConfig.class)
class SecurityConfigTest {

	private static final String UNMAPPED_NON_CUSTOMER_PATH = "/unmapped";
	private static final String CUSTOMERS = "/customers";
	private static final String CUSTOMER = "/customers/1";

	@Autowired
	private MockMvc mockMvc;

	@Test
	void healthEndpoint_isReachableWithoutToken() throws Exception {
		assertThat(statusOf(get("/actuator/health"))).isEqualTo(HttpStatus.NOT_FOUND.value());
	}

	@Test
	void healthSubPaths_areReachableWithoutToken() throws Exception {
		assertThat(statusOf(get("/actuator/health/liveness"))).isEqualTo(HttpStatus.NOT_FOUND.value());
	}

	@Test
	void apiDocsAndSwaggerUi_areReachableWithoutToken() throws Exception {
		assertThat(statusOf(get("/v3/api-docs"))).isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(statusOf(get("/v3/api-docs/swagger-config"))).isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(statusOf(get("/swagger-ui/index.html"))).isEqualTo(HttpStatus.NOT_FOUND.value());
	}

	@Test
	void otherEndpoints_withoutToken_returnUnauthorized() throws Exception {
		assertThat(statusOf(get(UNMAPPED_NON_CUSTOMER_PATH))).isEqualTo(HttpStatus.UNAUTHORIZED.value());
	}

	@Test
	void otherEndpoints_withJwt_passAuthentication() throws Exception {
		assertThat(statusOf(get(UNMAPPED_NON_CUSTOMER_PATH).with(jwt()))).isEqualTo(HttpStatus.NOT_FOUND.value());
	}

	@Test
	void otherEndpoints_doNotCreateSession() throws Exception {
		var session = mockMvc.perform(get(UNMAPPED_NON_CUSTOMER_PATH).with(jwt())).andReturn().getRequest().getSession(false);

		assertThat(session).isNull();
	}

	@Test
	void customerReads_withUserOrAdminRole_passAuthorization() throws Exception {
		assertThat(statusOf(get(CUSTOMERS).with(withRole("USER")))).isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(statusOf(get(CUSTOMER).with(withRole("USER")))).isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(statusOf(get(CUSTOMERS).with(withRole("ADMIN")))).isEqualTo(HttpStatus.NOT_FOUND.value());
	}

	@Test
	void customerWrites_withUserRole_returnForbidden() throws Exception {
		assertThat(statusOf(post(CUSTOMERS).with(withRole("USER")))).isEqualTo(HttpStatus.FORBIDDEN.value());
		assertThat(statusOf(put(CUSTOMER).with(withRole("USER")))).isEqualTo(HttpStatus.FORBIDDEN.value());
		assertThat(statusOf(patch(CUSTOMER).with(withRole("USER")))).isEqualTo(HttpStatus.FORBIDDEN.value());
		assertThat(statusOf(delete(CUSTOMER).with(withRole("USER")))).isEqualTo(HttpStatus.FORBIDDEN.value());
	}

	@Test
	void customerWrites_withAdminRole_passAuthorization() throws Exception {
		assertThat(statusOf(post(CUSTOMERS).with(withRole("ADMIN")))).isNotIn(401, 403);
		assertThat(statusOf(put(CUSTOMER).with(withRole("ADMIN")))).isNotIn(401, 403);
		assertThat(statusOf(delete(CUSTOMER).with(withRole("ADMIN")))).isNotIn(401, 403);
	}

	@Test
	void customerEndpoints_withJwtButNoRole_returnForbidden() throws Exception {
		assertThat(statusOf(get(CUSTOMERS).with(jwt()))).isEqualTo(HttpStatus.FORBIDDEN.value());
	}

	@Test
	void customerEndpoints_withoutToken_returnUnauthorized() throws Exception {
		assertThat(statusOf(get(CUSTOMERS))).isEqualTo(HttpStatus.UNAUTHORIZED.value());
		assertThat(statusOf(post(CUSTOMERS))).isEqualTo(HttpStatus.UNAUTHORIZED.value());
	}

	private int statusOf(RequestBuilder request) throws Exception {
		return mockMvc.perform(request).andReturn().getResponse().getStatus();
	}

	private static RequestPostProcessor withRole(String role) {
		return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
	}
}
