/*
 * Ticket: 02 (GitHub issue #3)
 *
 * Interfaces under test (do not exist yet):
 *   - com.datum.clientmanagement.security: SecurityFilterChain (stateless, CSRF disabled),
 *     Keycloak realm-role JwtAuthenticationConverter, ProblemDetail AuthenticationEntryPoint and AccessDeniedHandler
 *   - Endpoints: /customers/**, /actuator/health, /v3/api-docs, /swagger-ui/**
 *
 * Tokens: use spring-security-test jwt() with authorities for role checks; AC-2 needs a real JwtDecoder
 * (e.g. locally signed RSA key / NimbusJwtDecoder) to exercise expiry, signature and issuer validation.
 * Expected issuer in tests: whatever ISSUER_URI is in the test profile (referred to below as <issuer>).
 *
 * Authorization-pass checks (AC-4, AC-5) only assert "not 401 and not 403"; the business result
 * (200/201/204/404) belongs to ticket 03.
 *
 * Implementation note: the application's own (auto-configured) JwtDecoder is used unchanged. Its JWK set URI
 * points at an in-JVM WireMock server that publishes the public half of a locally generated RSA key, so signed
 * tokens go through the real signature, expiry and issuer validation and through the Keycloak role converter.
 */
package com.datum.clientmanagement.functional;

import com.datum.clientmanagement.TestcontainersConfiguration;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class SecurityFunctionalTest {

	private static final String KEY_ID = "test-signing-key";
	private static final String JWKS_PATH = "/realms/test/protocol/openid-connect/certs";
	private static final String WRONG_ISSUER = "http://evil.example/realms/other";
	private static final long VALID_FOR_SECONDS = 300;
	private static final long EXPIRED_SINCE_SECONDS = 60;

	private static final String CREATE_BODY =
			"{\"name\":\"Ana Souza\",\"cpf\":\"52998224725\",\"email\":\"ana@example.com\"}";
	private static final String ADMIN_CREATE_BODY =
			"{\"name\":\"Ana Souza\",\"cpf\":\"11144477735\",\"email\":\"ana@example.com\"}";
	private static final String UPDATE_BODY =
			"{\"name\":\"Ana Souza\",\"email\":\"ana@example.com\",\"status\":\"ACTIVE\",\"version\":0}";

	private static final RSAKey TRUSTED_KEY = generateRsaKey();
	// Same key id as the trusted key, so the decoder picks the trusted public key and the signature check fails.
	private static final RSAKey UNTRUSTED_KEY = generateRsaKey();
	private static final WireMockServer JWKS_SERVER = startJwksServer();

	@Autowired
	private MockMvc mockMvc;

	@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
	private String issuer;

	@DynamicPropertySource
	static void jwkSetUri(DynamicPropertyRegistry registry) {
		registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> JWKS_SERVER.baseUrl() + JWKS_PATH);
	}

	@AfterAll
	static void stopJwksServer() {
		JWKS_SERVER.stop();
	}

	// AC-1 (ticket 02) / NFR-1
	// Given no Authorization header
	// When each of: GET /customers, GET /customers/1, GET /customers/search?name=ana, GET /customers/1/score,
	//      POST /customers, PUT /customers/1, DELETE /customers/1 is called
	// Then every response is 401
	//  And Content-Type is "application/problem+json"
	//  And body has "status":401 and a non-empty "title"
	@Test
	@DisplayName("should return 401 problem+json when any /customers endpoint is called without a token")
	void noToken_returns401ProblemDetail() throws Exception {
		List<MockHttpServletRequestBuilder> requests = List.of(
				get("/customers"),
				get("/customers/1"),
				get("/customers/search?name=ana"),
				get("/customers/1/score"),
				jsonRequest(post("/customers"), CREATE_BODY),
				jsonRequest(put("/customers/1"), UPDATE_BODY),
				delete("/customers/1"));

		for (MockHttpServletRequestBuilder request : requests) {
			expectProblem(mockMvc.perform(request), 401)
					.andExpect(jsonPath("$.title", not(emptyOrNullString())));
		}
	}

	// AC-2 (ticket 02) / NFR-1 (positive control for the 401 cases below)
	// Given a JWT signed with the trusted key, issuer <issuer>, role ADMIN, exp = now + 300 s
	// When GET /customers is called with "Authorization: Bearer <that token>"
	// Then the response is 200, so the real decoder accepts a valid token and the 401s come from the broken claim
	@Test
	@DisplayName("should return 200 when the JWT is validly signed by the trusted key")
	void validSignedAdminToken_returns200() throws Exception {
		String token = signedToken(TRUSTED_KEY, claims -> { });

		mockMvc.perform(get("/customers").header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isOk());
	}

	// AC-2 (ticket 02) / NFR-1
	// Given a JWT signed with the trusted key, issuer <issuer>, role ADMIN, but "exp" = now - 60 s
	// When GET /customers is called with "Authorization: Bearer <that token>"
	// Then the response is 401 with Content-Type "application/problem+json"
	@Test
	@DisplayName("should return 401 when the JWT is expired")
	void expiredToken_returns401() throws Exception {
		Instant now = Instant.now();
		String token = signedToken(TRUSTED_KEY, claims -> claims
				.issuedAt(now.minusSeconds(VALID_FOR_SECONDS))
				.expiresAt(now.minusSeconds(EXPIRED_SINCE_SECONDS)));

		expectProblem(mockMvc.perform(get("/customers").header(HttpHeaders.AUTHORIZATION, bearer(token))), 401);
	}

	// AC-2 (ticket 02) / NFR-1
	// Given a JWT with valid claims (issuer <issuer>, role ADMIN, exp = now + 300 s) but signed with an UNTRUSTED key
	// When GET /customers is called with it
	// Then the response is 401 with Content-Type "application/problem+json"
	@Test
	@DisplayName("should return 401 when the JWT signature is invalid")
	void badSignature_returns401() throws Exception {
		String token = signedToken(UNTRUSTED_KEY, claims -> { });

		expectProblem(mockMvc.perform(get("/customers").header(HttpHeaders.AUTHORIZATION, bearer(token))), 401);
	}

	// AC-2 (ticket 02) / NFR-1
	// Given a JWT signed with the trusted key, role ADMIN, exp = now + 300 s, but "iss" = "http://evil.example/realms/other"
	// When GET /customers is called with it
	// Then the response is 401 with Content-Type "application/problem+json"
	@Test
	@DisplayName("should return 401 when the JWT issuer is wrong")
	void wrongIssuer_returns401() throws Exception {
		String token = signedToken(TRUSTED_KEY, claims -> claims.issuer(WRONG_ISSUER));

		expectProblem(mockMvc.perform(get("/customers").header(HttpHeaders.AUTHORIZATION, bearer(token))), 401);
	}

	// AC-3 (ticket 02) / NFR-1
	// Given a JWT with authority ROLE_USER only
	// When POST /customers {"name":"Ana Souza","cpf":"52998224725","email":"ana@example.com"} is called
	// Then the response is 403
	//  And Content-Type is "application/problem+json" with "status":403
	@Test
	@DisplayName("should return 403 problem+json when a USER calls POST /customers")
	void userPost_returns403() throws Exception {
		expectProblem(mockMvc.perform(jsonRequest(post("/customers"), CREATE_BODY).with(withRole("USER"))), 403);
	}

	// AC-3 (ticket 02) / NFR-1
	// Given a JWT with authority ROLE_USER only
	// When PUT /customers/1 {"name":"Ana Souza","email":"ana@example.com","status":"ACTIVE","version":0} is called
	// Then the response is 403 problem+json ("status":403)
	@Test
	@DisplayName("should return 403 problem+json when a USER calls PUT /customers/{id}")
	void userPut_returns403() throws Exception {
		expectProblem(mockMvc.perform(jsonRequest(put("/customers/1"), UPDATE_BODY).with(withRole("USER"))), 403);
	}

	// AC-3 (ticket 02) / NFR-1
	// Given a JWT with authority ROLE_USER only
	// When DELETE /customers/1 is called
	// Then the response is 403 problem+json ("status":403)
	@Test
	@DisplayName("should return 403 problem+json when a USER calls DELETE /customers/{id}")
	void userDelete_returns403() throws Exception {
		expectProblem(mockMvc.perform(delete("/customers/1").with(withRole("USER"))), 403);
	}

	// AC-4 (ticket 02)
	// Given a JWT with authority ROLE_USER only
	// When GET /customers, GET /customers/1, GET /customers/search?name=ana and GET /customers/1/score are called
	// Then no response is 401 or 403
	@Test
	@DisplayName("should pass authorization when a USER calls GET endpoints under /customers")
	void userGet_isAuthorized() throws Exception {
		List<String> readPaths = List.of("/customers", "/customers/1", "/customers/search?name=ana", "/customers/1/score");

		for (String path : readPaths)
			assertThat(statusOf(get(path).with(withRole("USER")))).as("GET %s", path).isNotIn(401, 403);
	}

	// AC-5 (ticket 02)
	// Given a JWT with authority ROLE_ADMIN
	// When GET /customers, POST /customers (valid body, cpf 11144477735), PUT /customers/1
	//      {"name":"Ana Souza","email":"ana@example.com","status":"ACTIVE","version":0}
	//      and DELETE /customers/1 are called
	// Then no response is 401 or 403
	@Test
	@DisplayName("should pass authorization when an ADMIN calls GET, POST, PUT or DELETE under /customers")
	void adminAllMethods_isAuthorized() throws Exception {
		assertThat(statusOf(get("/customers").with(withRole("ADMIN")))).as("GET").isNotIn(401, 403);
		assertThat(statusOf(jsonRequest(post("/customers"), ADMIN_CREATE_BODY).with(withRole("ADMIN"))))
				.as("POST").isNotIn(401, 403);
		assertThat(statusOf(jsonRequest(put("/customers/1"), UPDATE_BODY).with(withRole("ADMIN"))))
				.as("PUT").isNotIn(401, 403);
		assertThat(statusOf(delete("/customers/1").with(withRole("ADMIN")))).as("DELETE").isNotIn(401, 403);
	}

	// AC-6 (ticket 02) / NFR-2
	// Given no Authorization header
	// When GET /actuator/health, GET /v3/api-docs and GET /swagger-ui/index.html are called
	// Then none of the responses is 401 or 403 (health returns 200; api-docs returns 200)
	@Test
	@DisplayName("should allow access without a token when health, api-docs or swagger-ui is called")
	void publicEndpoints_allowAnonymous() throws Exception {
		mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
		mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
		assertThat(statusOf(get("/swagger-ui/index.html"))).isNotIn(401, 403);
	}

	// Edge case (ticket 02)
	// Given a validly signed JWT with NO "realm_access" claim
	// When GET /customers is called
	// Then the response is 403 problem+json (authenticated but no role)
	@Test
	@DisplayName("should return 403 when the JWT has no realm_access claim")
	void tokenWithoutRealmAccess_returns403() throws Exception {
		String token = signedToken(TRUSTED_KEY, claims -> claims.claims(all -> all.remove("realm_access")));

		expectProblem(mockMvc.perform(get("/customers").header(HttpHeaders.AUTHORIZATION, bearer(token))), 403);
	}

	// Edge case (ticket 02)
	// Given a validly signed JWT with realm_access.roles = ["admin", "user"] (lowercase)
	// When GET /customers and POST /customers are called
	// Then both responses are 403 (only exact "USER"/"ADMIN" grant access)
	@Test
	@DisplayName("should return 403 when role names have the wrong casing")
	void wrongRoleCasing_returns403() throws Exception {
		String token = signedToken(TRUSTED_KEY,
				claims -> claims.claim("realm_access", Map.of("roles", List.of("admin", "user"))));

		mockMvc.perform(get("/customers").header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isForbidden());
		mockMvc.perform(jsonRequest(post("/customers"), CREATE_BODY).header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isForbidden());
	}

	// Edge case (ticket 02)
	// Given no Authorization header
	// When GET /does-not-exist is called
	// Then the response is 401 problem+json (not 404)
	@Test
	@DisplayName("should return 401 instead of 404 when an unknown path is called without a token")
	void unknownPathNoToken_returns401() throws Exception {
		expectProblem(mockMvc.perform(get("/does-not-exist")), 401);
	}

	private static ResultActions expectProblem(ResultActions result, int expectedStatus) throws Exception {
		return result
				.andExpect(status().is(expectedStatus))
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(expectedStatus));
	}

	private int statusOf(MockHttpServletRequestBuilder request) throws Exception {
		return mockMvc.perform(request).andReturn().getResponse().getStatus();
	}

	private static MockHttpServletRequestBuilder jsonRequest(MockHttpServletRequestBuilder request, String body) {
		return request.contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private static RequestPostProcessor withRole(String role) {
		return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}

	/** ADMIN token valid for 300 s from the expected issuer; the customizer overrides individual claims. */
	private String signedToken(RSAKey signingKey, Consumer<JwtClaimsSet.Builder> claimsCustomizer) {
		Instant now = Instant.now();
		JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
				.issuer(issuer)
				.subject("test-user")
				.issuedAt(now)
				.expiresAt(now.plusSeconds(VALID_FOR_SECONDS))
				.claim("realm_access", Map.of("roles", List.of("ADMIN")));
		claimsCustomizer.accept(claims);
		JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(KEY_ID).build();
		NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKey)));
		return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
	}

	private static RSAKey generateRsaKey() {
		try {
			return new RSAKeyGenerator(RSAKeyGenerator.MIN_KEY_SIZE_BITS).keyID(KEY_ID).generate();
		} catch (JOSEException e) {
			throw new IllegalStateException("Could not generate the test RSA key", e);
		}
	}

	private static WireMockServer startJwksServer() {
		WireMockServer server = new WireMockServer(wireMockConfig().dynamicPort());
		server.start();
		server.stubFor(WireMock.get(urlEqualTo(JWKS_PATH)).willReturn(aResponse()
				.withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
				.withBody(new JWKSet(TRUSTED_KEY.toPublicJWK()).toString())));
		return server;
	}
}
