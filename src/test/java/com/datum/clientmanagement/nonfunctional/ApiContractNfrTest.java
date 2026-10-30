/*
 * NFR-13, NFR-14 (docs/PRODUCT.md); tickets 02, 03, 04, 05 (GitHub issues #3, #4, #5, #6).
 *
 * Interfaces under test: @RestControllerAdvice in com.datum.clientmanagement.common.error, all /customers endpoints.
 * ProblemDetail fields: title, status, detail, instance [, errors[{field, message}]].
 */
package com.datum.clientmanagement.nonfunctional;

import com.datum.clientmanagement.ScoreWireMockConfiguration;
import com.datum.clientmanagement.TestcontainersConfiguration;
import com.datum.clientmanagement.util.CustomerFormats;
import com.datum.clientmanagement.service.ScoreClient;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ScoreWireMockConfiguration.class})
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"}) // IDE has no database; the SQL runs against Testcontainers Postgres
class ApiContractNfrTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String CUSTOMERS = "/customers";
	private static final String SEARCH = "/customers/search";
	private static final String INSERT_CUSTOMER = "INSERT INTO customers (name, name_normalized, cpf, email, status,"
			+ " created_at, updated_at) VALUES (?, ?, ?, ?, 'ACTIVE', now(), now())";
	private static final String ANA_CPF = "52998224725";
	private static final String ANA_BODY = "{\"name\":\"Ana Souza\",\"cpf\":\"52998224725\",\"email\":\"ana@example.com\"}";
	private static final int READ_TIMEOUT_EXCEEDING_DELAY_MS = 3000;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private WireMockServer scoreWireMock;

	@Autowired
	private CircuitBreakerRegistry circuitBreakerRegistry;

	// Real client; only used to force an unexpected exception for the 500 case.
	@MockitoSpyBean
	private ScoreClient scoreClient;

	@BeforeEach
	void resetState() {
		jdbcTemplate.update("DELETE FROM customers");
		scoreWireMock.resetAll();
		circuitBreakerRegistry.circuitBreaker(ScoreClient.RESILIENCE_INSTANCE).reset();
	}

	// NFR-13:api
	// Given requests that produce each error status:
	//   400 (POST /customers with cpf "52998224726"), 401 (no token), 403 (USER POST), 404 (GET /customers/999999),
	//   409 (duplicate cpf 52998224725), 422 (PUT with different cpf), 500 (forced failure),
	//   502/503/504 (Score via WireMock)
	// When each response is inspected
	// Then Content-Type is "application/problem+json"
	//  And the body has "title" (non-empty), "status" equal to the HTTP status,
	//      "detail" (string) and "instance" equal to the request path (e.g. "/customers/999999")
	@Test
	@DisplayName("should return an RFC 7807 ProblemDetail with all standard fields for every error status")
	void everyError_isProblemDetail() throws Exception {
		jdbcTemplate.update(INSERT_CUSTOMER, "Ana Souza", "ana souza", ANA_CPF, "ana@example.com");
		long id = jdbcTemplate.queryForObject("SELECT id FROM customers WHERE cpf = ?", Long.class, ANA_CPF);
		String customerPath = CUSTOMERS + "/" + id;
		String scorePath = customerPath + "/score";

		assertProblem(admin(jsonRequest(post(CUSTOMERS),
				"{\"name\":\"Ana Souza\",\"cpf\":\"52998224726\",\"email\":\"ana@example.com\"}")), 400, CUSTOMERS);
		assertProblem(get(CUSTOMERS), 401, CUSTOMERS);
		assertProblem(user(jsonRequest(post(CUSTOMERS), ANA_BODY)), 403, CUSTOMERS);
		assertProblem(user(get(CUSTOMERS + "/999999")), 404, CUSTOMERS + "/999999");
		assertProblem(admin(jsonRequest(post(CUSTOMERS), ANA_BODY)), 409, CUSTOMERS);
		assertProblem(admin(jsonRequest(put(customerPath), "{\"name\":\"Ana Souza\",\"email\":\"ana@example.com\","
				+ "\"status\":\"ACTIVE\",\"version\":0,\"cpf\":\"11144477735\"}")), 422, customerPath);

		doThrow(new IllegalStateException("forced failure")).when(scoreClient).fetch(anyString());
		assertProblem(user(get(scorePath)), 500, scorePath);
		reset(scoreClient);

		stubScore(aResponse().withStatus(400));
		assertProblem(user(get(scorePath)), 502, scorePath);
		stubScore(aResponse().withStatus(500));
		assertProblem(user(get(scorePath)), 503, scorePath);
		stubScore(okJson("{\"cpf\":\"52998224725\",\"score\":750,\"classification\":\"GOOD\"}")
				.withFixedDelay(READ_TIMEOUT_EXCEEDING_DELAY_MS));
		assertProblem(user(get(scorePath)), 504, scorePath);
	}

	// NFR-13:api
	// Given an ADMIN
	// When POST /customers {"name":"","cpf":"123","email":"x"}
	// Then the 400 body has errors[] of length 3, each element with non-empty "field" and "message",
	//      and the fields are exactly {"name","cpf","email"}
	@Test
	@DisplayName("should include errors[] with field and message when validation fails")
	void validationErrors_includeFieldAndMessage() throws Exception {
		String body = mockMvc.perform(post("/customers")
						.with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"\",\"cpf\":\"123\",\"email\":\"x\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andReturn().getResponse().getContentAsString();

		JsonNode errors = JSON.readTree(body).path("errors");
		assertThat(errors.isArray()).isTrue();
		assertThat(errors.size()).isEqualTo(3);
		List<String> fields = new ArrayList<>();
		for (JsonNode error : errors) {
			assertThat(error.path("field").asString()).isNotBlank();
			assertThat(error.path("message").asString()).isNotBlank();
			fields.add(error.path("field").asString());
		}
		assertThat(fields).containsExactlyInAnyOrder("name", "cpf", "email");
	}

	// NFR-14:api — no clamping
	// Given 30 customers
	// When GET /customers?size=1000 and GET /customers/search?name=a&size=1000
	// Then each response is 400 problem+json ("status":400, "instance" == request path)
	//  And errors[] has exactly 1 entry with field "size" and a non-empty message
	//  And no page of data is returned (body has no "content")
	@Test
	@DisplayName("should reject an oversized page with a 400 ProblemDetail instead of clamping it")
	void oversizedPage_isRejectedNotClamped() throws Exception {
		seedCustomers(30);

		Map<String, MockHttpServletRequestBuilder> requestsByPath = Map.of(
				CUSTOMERS, user(get(CUSTOMERS).param("size", "1000")),
				SEARCH, user(get(SEARCH).param("name", "a").param("size", "1000")));

		for (Map.Entry<String, MockHttpServletRequestBuilder> entry : requestsByPath.entrySet()) {
			String path = entry.getKey();
			JsonNode problem = badRequestProblem(mockMvc.perform(entry.getValue()).andReturn().getResponse());

			assertThat(problem.path("instance").asString()).isEqualTo(path);
			JsonNode errors = problem.path("errors");
			assertThat(errors.size()).as("errors[] of %s", path).isEqualTo(1);
			assertThat(errors.get(0).path("field").asString()).isEqualTo("size");
			assertThat(errors.get(0).path("message").asString()).isNotBlank();
			assertThat(problem.has("content")).as("page data in %s", path).isFalse();
		}
	}

	// NFR-14:api
	// Given 30 customers
	// When GET /customers?page=-1&size=0
	// Then the response is 400 problem+json with exactly 2 errors[] entries: field "page" and field "size"
	@Test
	@DisplayName("should report both page and size in errors[] when both are out of range")
	void invalidPageAndSize_reportBothFields() throws Exception {
		seedCustomers(30);

		MockHttpServletResponse response = mockMvc.perform(user(get(CUSTOMERS).param("page", "-1").param("size", "0")))
				.andReturn().getResponse();

		JsonNode errors = badRequestProblem(response).path("errors");
		List<String> fields = new ArrayList<>();
		errors.forEach(error -> fields.add(error.path("field").asString()));
		assertThat(fields).containsExactlyInAnyOrder("page", "size");
	}

	// NFR-14:api
	// Given 30 customers
	// When GET /customers (no page/size)
	// Then the response is 200 with page.number == 0, page.size == 20, content.length == 20
	@Test
	@DisplayName("should default to page 0 and size 20 on list when paging parameters are omitted")
	void listDefaultsPage0Size20() throws Exception {
		seedCustomers(30);

		assertFirstPageOf20(user(get(CUSTOMERS)));
	}

	// NFR-14:api
	// Given 30 customers matching "a"
	// When GET /customers/search?name=a (no page/size)
	// Then the response is 200 with page.number == 0, page.size == 20 and content.length == 20
	@Test
	@DisplayName("should default the page size to 20 on search when size is omitted")
	void searchDefaultSize20() throws Exception {
		seedCustomers(30);

		assertFirstPageOf20(user(get(SEARCH).param("name", "a")));
	}

	// NFR-14:api
	// Given any data
	// When GET /customers?status=active (lowercase) and GET /customers?status=
	// Then the response is 400 problem+json
	// (Assumes status matching is case-sensitive and an empty value is invalid — flagged in report.)
	@Test
	@DisplayName("should return 400 when status has an invalid value such as lowercase or empty")
	void invalidStatusVariants_return400() throws Exception {
		seedCustomers(1);

		for (String status : List.of("active", "")) {
			MockHttpServletResponse response = mockMvc.perform(user(get(CUSTOMERS).param("status", status)))
					.andReturn().getResponse();

			assertThat(badRequestProblem(response).path("status").asInt()).as("status=%s", status).isEqualTo(400);
		}
	}

	// Every name contains "a", so the rows also match a search for "a".
	private void seedCustomers(int count) {
		for (int i = 1; i <= count; i++) {
			String name = "Ana %02d".formatted(i);
			jdbcTemplate.update(INSERT_CUSTOMER, name, CustomerFormats.searchableName(name), "%011d".formatted(i),
					"ana" + i + "@example.com");
		}
	}

	private void assertFirstPageOf20(MockHttpServletRequestBuilder request) throws Exception {
		MockHttpServletResponse response = mockMvc.perform(request).andReturn().getResponse();
		assertThat(response.getStatus()).isEqualTo(200);

		JsonNode body = JSON.readTree(response.getContentAsString());
		assertThat(body.path("page").path("number").asInt()).isZero();
		assertThat(body.path("page").path("size").asInt()).isEqualTo(20);
		assertThat(body.path("content").size()).isEqualTo(20);
	}

	private static JsonNode badRequestProblem(MockHttpServletResponse response) throws Exception {
		String body = response.getContentAsString();
		assertThat(response.getStatus()).as("status; body: %s", body).isEqualTo(400);
		assertThat(MediaType.parseMediaType(response.getContentType()).isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.as("content type %s", response.getContentType()).isTrue();
		JsonNode problem = JSON.readTree(body);
		assertThat(problem.path("status").asInt()).isEqualTo(400);
		return problem;
	}

	private void assertProblem(MockHttpServletRequestBuilder request, int expectedStatus, String expectedInstance)
			throws Exception {
		MockHttpServletResponse response = mockMvc.perform(request).andReturn().getResponse();
		String body = response.getContentAsString();

		assertThat(response.getStatus()).as("status; body: %s", body).isEqualTo(expectedStatus);
		assertThat(MediaType.parseMediaType(response.getContentType()).isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.as("content type of the %d response", expectedStatus).isTrue();
		JsonNode problem = JSON.readTree(body);
		assertThat(problem.path("title").asString()).as("title of %d", expectedStatus).isNotBlank();
		assertThat(problem.path("status").asInt()).as("status field of %d", expectedStatus).isEqualTo(expectedStatus);
		assertThat(problem.path("detail").isString()).as("detail of %d is a string; body: %s", expectedStatus, body)
				.isTrue();
		assertThat(problem.path("instance").asString()).as("instance of %d", expectedStatus)
				.isEqualTo(expectedInstance);
	}

	private void stubScore(ResponseDefinitionBuilder response) {
		scoreWireMock.resetAll();
		scoreWireMock.stubFor(WireMock.get("/scores/" + ANA_CPF).willReturn(response));
	}

	private static MockHttpServletRequestBuilder jsonRequest(MockHttpServletRequestBuilder request, String body) {
		return request.contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private static MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
		return request.with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")));
	}

	private static MockHttpServletRequestBuilder user(MockHttpServletRequestBuilder request) {
		return request.with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")));
	}
}
