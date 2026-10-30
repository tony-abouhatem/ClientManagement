/*
 * Ticket: 04 (GitHub issue #5)
 *
 * Interfaces under test:
 *   - GET /customers?status=&name=&page=&size=&sort=
 *   - GET /customers/search?name=&page=&size=&sort=
 *   - Response shape: {"content":[CustomerResponse...], "page":{"size","number","totalElements","totalPages"}}
 *
 * All requests use a USER jwt(). Data is seeded per test in the Testcontainers Postgres.
 */
package com.datum.clientmanagement.functional;

import com.datum.clientmanagement.TestcontainersConfiguration;
import com.datum.clientmanagement.util.CustomerFormats;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"}) // IDE has no database; the SQL runs against Testcontainers Postgres
class CustomerListSearchFunctionalTest {

	private static final String CUSTOMERS = "/customers";
	private static final String SEARCH = "/customers/search";
	private static final String ACTIVE = "ACTIVE";
	private static final String INACTIVE = "INACTIVE";
	private static final String INSERT_CUSTOMER = "INSERT INTO customers"
			+ " (name, name_normalized, cpf, email, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, now(), now())";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private int insertedCustomers;

	@BeforeEach
	void deleteAllCustomers() {
		jdbcTemplate.update("DELETE FROM customers");
	}

	// AC-1 (ticket 04) / NFR-14
	// Given 25 customers
	// When GET /customers (no parameters)
	// Then the response is 200 with content.length == 20
	//  And page == {size:20, number:0, totalElements:25, totalPages:2}
	@Test
	@DisplayName("should return the first 20 of 25 customers with page metadata when no parameters are given")
	void listDefaults_returnsFirstPageOf20() throws Exception {
		seedCustomers(25);

		JsonNode body = okPage(request(CUSTOMERS));

		assertThat(body.path("content").size()).isEqualTo(20);
		JsonNode page = body.path("page");
		assertThat(page.path("size").asInt()).isEqualTo(20);
		assertThat(page.path("number").asInt()).isZero();
		assertThat(page.path("totalElements").asLong()).isEqualTo(25);
		assertThat(page.path("totalPages").asInt()).isEqualTo(2);
	}

	// AC-1 (ticket 04)
	// Given 25 customers
	// When GET /customers?page=1
	// Then content.length == 5 and page.number == 1
	@Test
	@DisplayName("should return the remaining 5 customers when page=1 is requested")
	void listSecondPage_returnsRemaining() throws Exception {
		seedCustomers(25);

		JsonNode body = okPage(request(CUSTOMERS, "page", "1"));

		assertThat(body.path("content").size()).isEqualTo(5);
		assertThat(body.path("page").path("number").asInt()).isEqualTo(1);
	}

	// AC-2 (ticket 04)
	// Given 3 ACTIVE and 2 INACTIVE customers
	// When GET /customers?status=ACTIVE
	// Then content.length == 3, every item has status "ACTIVE", page.totalElements == 3
	@Test
	@DisplayName("should return only ACTIVE customers when status=ACTIVE")
	void filterByStatus_returnsOnlyMatching() throws Exception {
		insertCustomer("Ana Souza", ACTIVE);
		insertCustomer("Bruno Lima", INACTIVE);
		insertCustomer("Carla Dias", ACTIVE);
		insertCustomer("Diego Rocha", INACTIVE);
		insertCustomer("Elisa Melo", ACTIVE);

		JsonNode body = okPage(request(CUSTOMERS, "status", ACTIVE));

		assertThat(body.path("content").size()).isEqualTo(3);
		for (JsonNode customer : body.path("content"))
			assertThat(customer.path("status").asString()).isEqualTo(ACTIVE);
		assertThat(body.path("page").path("totalElements").asLong()).isEqualTo(3);
	}

	// AC-3 (ticket 04) / NFR-14
	// Given any data
	// When GET /customers?status=FOO
	// Then the response is 400 problem+json with "status":400
	@Test
	@DisplayName("should return 400 problem when status is not a valid value")
	void invalidStatus_returns400() throws Exception {
		insertCustomer("Ana Souza", ACTIVE);

		badRequestProblem(request(CUSTOMERS, "status", "FOO"));
	}

	// AC-4 (ticket 04)
	// Given customers "João da Silva" and "Maria Souza"
	// When GET /customers/search?name=joao, ?name=JOÃO, ?name=silva and ?name=ão%20d are each called
	// Then each response is 200 and content contains exactly "João da Silva" (not "Maria Souza")
	@Test
	@DisplayName("should find João da Silva when searching case- and accent-insensitively by part of the name")
	void searchCaseAndAccentInsensitive_returnsMatch() throws Exception {
		insertCustomer("João da Silva", ACTIVE);
		insertCustomer("Maria Souza", ACTIVE);

		for (String query : List.of("joao", "JOÃO", "silva", "ão d")) {
			JsonNode body = okPage(request(SEARCH, "name", query));

			assertThat(names(body)).as("search for '%s'", query).containsExactly("João da Silva");
		}
	}

	// AC-5 (ticket 04)
	// Given any data
	// When GET /customers/search (no name), ?name= and ?name=%20%20 are each called
	// Then each response is 400 problem+json with "status":400
	@Test
	@DisplayName("should return 400 when search name is missing or blank")
	void searchMissingOrBlankName_returns400() throws Exception {
		insertCustomer("Ana Souza", ACTIVE);

		badRequestProblem(request(SEARCH));
		badRequestProblem(request(SEARCH, "name", ""));
		badRequestProblem(request(SEARCH, "name", "  "));
	}

	// AC-6 (ticket 04)
	// Given "João da Silva" ACTIVE, "João Pereira" INACTIVE, "Maria Souza" INACTIVE
	// When GET /customers?name=joao&status=INACTIVE
	// Then content contains exactly "João Pereira"
	@Test
	@DisplayName("should apply name and status filters together when both are given")
	void nameAndStatusFilters_areAnded() throws Exception {
		insertCustomer("João da Silva", ACTIVE);
		insertCustomer("João Pereira", INACTIVE);
		insertCustomer("Maria Souza", INACTIVE);

		JsonNode body = okPage(request(CUSTOMERS, "name", "joao", "status", INACTIVE));

		assertThat(names(body)).containsExactly("João Pereira");
	}

	// AC-7 (ticket 04) / NFR-14 — no clamping: out-of-range paging is rejected
	// Given 25 customers
	// When each of these is called separately on GET /customers:
	//   ?size=0    -> errors[] has exactly 1 entry with field "size"
	//   ?size=-5   -> errors[] has exactly 1 entry with field "size"
	//   ?size=101  -> errors[] has exactly 1 entry with field "size"
	//   ?page=-1   -> errors[] has exactly 1 entry with field "page"
	// Then each response is 400 problem+json with "status":400 and the errors[] entry above (non-empty "message")
	@Test
	@DisplayName("should return 400 naming the field when /customers gets size=0, size=-5, size=101 or page=-1")
	void listOutOfRangePaging_returns400() throws Exception {
		seedCustomers(25);

		assertSingleFieldError(request(CUSTOMERS, "size", "0"), "size");
		assertSingleFieldError(request(CUSTOMERS, "size", "-5"), "size");
		assertSingleFieldError(request(CUSTOMERS, "size", "101"), "size");
		assertSingleFieldError(request(CUSTOMERS, "page", "-1"), "page");
	}

	// AC-7 (ticket 04) / NFR-14
	// Given customer "João da Silva"
	// When each of these is called separately:
	//   GET /customers/search?name=joao&size=0    -> errors[].field "size"
	//   GET /customers/search?name=joao&size=-5   -> errors[].field "size"
	//   GET /customers/search?name=joao&size=101  -> errors[].field "size"
	//   GET /customers/search?name=joao&page=-1   -> errors[].field "page"
	// Then each response is 400 problem+json with "status":400 and exactly that one errors[] entry
	@Test
	@DisplayName("should return 400 naming the field when /customers/search gets size=0, size=-5, size=101 or page=-1")
	void searchOutOfRangePaging_returns400() throws Exception {
		insertCustomer("João da Silva", ACTIVE);

		assertSingleFieldError(request(SEARCH, "name", "joao", "size", "0"), "size");
		assertSingleFieldError(request(SEARCH, "name", "joao", "size", "-5"), "size");
		assertSingleFieldError(request(SEARCH, "name", "joao", "size", "101"), "size");
		assertSingleFieldError(request(SEARCH, "name", "joao", "page", "-1"), "page");
	}

	// AC-7 (ticket 04) / NFR-14 — boundaries
	// Given 150 customers
	// When GET /customers?size=1, GET /customers?size=100 and GET /customers?page=0
	// Then the responses are 200 with page.size 1 (content.length 1), page.size 100 (content.length 100),
	//      and page.number 0 / page.size 20 respectively
	@Test
	@DisplayName("should accept size=1, size=100 and page=0 when they are at the allowed boundaries")
	void pagingBoundaries_areAccepted() throws Exception {
		seedCustomers(150);

		JsonNode smallest = okPage(request(CUSTOMERS, "size", "1"));
		assertThat(smallest.path("page").path("size").asInt()).isEqualTo(1);
		assertThat(smallest.path("content").size()).isEqualTo(1);

		JsonNode largest = okPage(request(CUSTOMERS, "size", "100"));
		assertThat(largest.path("page").path("size").asInt()).isEqualTo(100);
		assertThat(largest.path("content").size()).isEqualTo(100);

		JsonNode firstPage = okPage(request(CUSTOMERS, "page", "0"));
		assertThat(firstPage.path("page").path("number").asInt()).isZero();
		assertThat(firstPage.path("page").path("size").asInt()).isEqualTo(20);
	}

	// AC-8 (ticket 04)
	// Given customers created in this order: "Carla", "Ana", "Bruno"
	// When GET /customers?sort=name,asc
	// Then content names are ["Ana", "Bruno", "Carla"]
	@Test
	@DisplayName("should order results by name when sort=name,asc")
	void sortByName_ordersByName() throws Exception {
		insertCustomers("Carla", "Ana", "Bruno");

		JsonNode body = okPage(request(CUSTOMERS, "sort", "name,asc"));

		assertThat(names(body)).containsExactly("Ana", "Bruno", "Carla");
	}

	// AC-8 (ticket 04)
	// Given customers created in this order: "Carla", "Ana", "Bruno" (ids ascending)
	// When GET /customers (no sort)
	// Then content names are ["Carla", "Ana", "Bruno"] (id ascending)
	@Test
	@DisplayName("should order results by id ascending when no sort is given")
	void defaultSort_isIdAsc() throws Exception {
		insertCustomers("Carla", "Ana", "Bruno");

		JsonNode body = okPage(request(CUSTOMERS));

		assertThat(names(body)).containsExactly("Carla", "Ana", "Bruno");
		List<Long> ids = new ArrayList<>();
		body.path("content").forEach(customer -> ids.add(customer.path("id").asLong()));
		assertThat(ids).isSorted();
	}

	// Edge case (ticket 04)
	// Given any data
	// When GET /customers?sort=password,asc
	// Then the response is 400 problem+json
	@Test
	@DisplayName("should return 400 when sorting by an unknown property")
	void sortByUnknownProperty_returns400() throws Exception {
		insertCustomer("Ana Souza", ACTIVE);

		badRequestProblem(request(CUSTOMERS, "sort", "password,asc"));
	}

	// AC-9 (ticket 04)
	// Given customer "João da Silva" only
	// When GET /customers/search?name=zzz and GET /customers?name=zzz
	// Then each response is 200 with content == [] and page.totalElements == 0
	@Test
	@DisplayName("should return 200 with empty content when nothing matches")
	void noMatches_returnsEmptyPage() throws Exception {
		insertCustomer("João da Silva", ACTIVE);

		for (String path : List.of(SEARCH, CUSTOMERS)) {
			JsonNode body = okPage(request(path, "name", "zzz"));

			assertThat(body.path("content").isArray()).as(path).isTrue();
			assertThat(body.path("content").isEmpty()).as(path).isTrue();
			assertThat(body.path("page").path("totalElements").asLong()).as(path).isZero();
		}
	}

	// Edge case (ticket 04): SQL wildcards are literal
	// Given customers "Ana Souza" and "50% Off Ltda" and "a_b Comercio"
	// When GET /customers/search?name=%25 (i.e. "%")
	// Then content contains exactly "50% Off Ltda"
	// When GET /customers/search?name=_
	// Then content contains exactly "a_b Comercio"
	@Test
	@DisplayName("should match % and _ literally when they appear in the search name")
	void sqlWildcardsMatchedLiterally() throws Exception {
		insertCustomers("Ana Souza", "50% Off Ltda", "a_b Comercio");

		assertThat(names(okPage(request(SEARCH, "name", "%")))).containsExactly("50% Off Ltda");
		assertThat(names(okPage(request(SEARCH, "name", "_")))).containsExactly("a_b Comercio");
	}

	private void seedCustomers(int count) {
		for (int i = 1; i <= count; i++)
			insertCustomer("Customer %03d".formatted(i), ACTIVE);
	}

	private void insertCustomers(String... names) {
		for (String name : names)
			insertCustomer(name, ACTIVE);
	}

	// One row per call, so ids ascend in insertion order.
	private void insertCustomer(String name, String status) {
		insertedCustomers++;
		jdbcTemplate.update(INSERT_CUSTOMER, name, CustomerFormats.searchableName(name),
				"%011d".formatted(insertedCustomers), "customer" + insertedCustomers + "@example.com", status);
	}

	private JsonNode okPage(MockHttpServletRequestBuilder request) throws Exception {
		MockHttpServletResponse response = mockMvc.perform(request).andReturn().getResponse();
		String body = response.getContentAsString();
		assertThat(response.getStatus()).as("status; body: %s", body).isEqualTo(200);
		return JSON.readTree(body);
	}

	private JsonNode badRequestProblem(MockHttpServletRequestBuilder request) throws Exception {
		MockHttpServletResponse response = mockMvc.perform(request).andReturn().getResponse();
		String body = response.getContentAsString();
		assertThat(response.getStatus()).as("status; body: %s", body).isEqualTo(400);
		assertThat(MediaType.parseMediaType(response.getContentType()).isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.as("content type %s", response.getContentType()).isTrue();
		JsonNode problem = JSON.readTree(body);
		assertThat(problem.path("status").asInt()).isEqualTo(400);
		return problem;
	}

	private void assertSingleFieldError(MockHttpServletRequestBuilder request, String field) throws Exception {
		JsonNode errors = badRequestProblem(request).path("errors");

		assertThat(errors.size()).as("errors[]: %s", errors).isEqualTo(1);
		assertThat(errors.get(0).path("field").asString()).isEqualTo(field);
		assertThat(errors.get(0).path("message").asString()).isNotBlank();
	}

	private static List<String> names(JsonNode body) {
		List<String> names = new ArrayList<>();
		body.path("content").forEach(customer -> names.add(customer.path("name").asString()));
		return names;
	}

	/** GET {@code path} as USER, with the query parameters given as name/value pairs. */
	private static MockHttpServletRequestBuilder request(String path, String... namesAndValues) {
		MockHttpServletRequestBuilder request = get(path)
				.with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")));
		for (int i = 0; i < namesAndValues.length; i += 2)
			request.param(namesAndValues[i], namesAndValues[i + 1]);
		return request;
	}
}
