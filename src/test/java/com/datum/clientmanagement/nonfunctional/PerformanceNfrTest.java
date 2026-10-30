/*
 * NFR-15 (docs/PRODUCT.md); tickets 03 and 04 (GitHub issues #4, #5).
 *
 * PRODUCT.md says this target is "measured informally; no load-test tooling required". These scenarios are a
 * lightweight in-JVM check (sequential requests, p95 computed in the test). Suggested: tag them so they can be
 * excluded on slow machines — see the flagged assumption in the report.
 */
package com.datum.clientmanagement.nonfunctional;

import com.datum.clientmanagement.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"}) // IDE has no database; the SQL runs against Testcontainers Postgres
class PerformanceNfrTest {

	private static final int SEEDED_CUSTOMERS = 10_000;
	private static final int WARM_UP_REQUESTS = 20;
	private static final int GET_REQUESTS = 200;
	private static final int POST_REQUESTS = 100;
	private static final int PUT_REQUESTS = 100;
	private static final int PAGE_SIZE = 20;
	private static final Duration P95_TARGET = Duration.ofMillis(300);
	private static final double P95 = 0.95;
	private static final long RANDOM_SEED = 42L;
	// Seeded rows use CPFs 00000000000..00000009999; generated valid CPFs start far above that range.
	private static final int FIRST_GENERATED_CPF_BASE = 100_000_000;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final Random random = new Random(RANDOM_SEED);

	@BeforeEach
	void deleteAllCustomers() {
		jdbcTemplate.update("DELETE FROM customers");
	}

	// NFR-15:performance
	// Given 10 000 customers seeded in Testcontainers Postgres (about half named with accents, e.g. "João N")
	//  And 20 warm-up requests already made
	// When 200 sequential GET /customers?page=<random 0..499>&size=20 requests and
	//      200 sequential GET /customers/search?name=joao requests are made as USER
	// Then the p95 latency of each series is < 300 ms
	@Test
	@DisplayName("should keep list and search p95 under 300 ms when there are 10k customers")
	void listAndSearchP95Under300ms() throws Exception {
		seedCustomers();
		for (int i = 0; i < WARM_UP_REQUESTS; i++) {
			mockMvc.perform(get("/customers").with(role("USER")));
			mockMvc.perform(get("/customers/search").param("name", "joao").with(role("USER")));
		}

		List<Duration> listLatencies = new ArrayList<>();
		for (int i = 0; i < GET_REQUESTS; i++) {
			String page = String.valueOf(random.nextInt(SEEDED_CUSTOMERS / PAGE_SIZE));
			listLatencies.add(timed(get("/customers").param("page", page).param("size", String.valueOf(PAGE_SIZE))
					.with(role("USER")), 200));
		}
		List<Duration> searchLatencies = new ArrayList<>();
		for (int i = 0; i < GET_REQUESTS; i++)
			searchLatencies.add(timed(get("/customers/search").param("name", "joao").with(role("USER")), 200));

		assertThat(p95(listLatencies)).as("list p95").isLessThan(P95_TARGET);
		assertThat(p95(searchLatencies)).as("search p95").isLessThan(P95_TARGET);
	}

	// NFR-15:performance
	// Given 10 000 customers seeded
	// When 200 sequential GET /customers/{random id}, 100 POST /customers (unique valid CPFs) and 100 PUT requests are made
	// Then the p95 latency of each operation type is < 300 ms
	@Test
	@DisplayName("should keep CRUD p95 under 300 ms when there are 10k customers")
	void crudP95Under300ms() throws Exception {
		List<Long> ids = seedCustomers();
		for (int i = 0; i < WARM_UP_REQUESTS; i++)
			mockMvc.perform(get("/customers/" + randomId(ids)).with(role("USER")));

		List<Duration> getLatencies = new ArrayList<>();
		for (int i = 0; i < GET_REQUESTS; i++)
			getLatencies.add(timed(get("/customers/" + randomId(ids)).with(role("USER")), 200));
		List<Duration> postLatencies = new ArrayList<>();
		for (int i = 0; i < POST_REQUESTS; i++) {
			String body = "{\"name\":\"Perf " + i + "\",\"cpf\":\"" + validCpf(FIRST_GENERATED_CPF_BASE + i)
					+ "\",\"email\":\"perf" + i + "@example.com\"}";
			postLatencies.add(timed(json(post("/customers"), body), 201));
		}
		List<Duration> putLatencies = new ArrayList<>();
		for (int i = 0; i < PUT_REQUESTS; i++) {
			// Seeded rows are at version 0 and each id is updated once.
			String body = "{\"name\":\"Updated " + i + "\",\"email\":\"updated" + i + "@example.com\","
					+ "\"status\":\"INACTIVE\",\"version\":0}";
			putLatencies.add(timed(json(put("/customers/" + ids.get(i)), body), 200));
		}

		assertThat(p95(getLatencies)).as("GET p95").isLessThan(P95_TARGET);
		assertThat(p95(postLatencies)).as("POST p95").isLessThan(P95_TARGET);
		assertThat(p95(putLatencies)).as("PUT p95").isLessThan(P95_TARGET);
	}

	private List<Long> seedCustomers() {
		List<Object[]> rows = new ArrayList<>(SEEDED_CUSTOMERS);
		for (int i = 0; i < SEEDED_CUSTOMERS; i++) {
			boolean accented = i % 2 == 0;
			String name = (accented ? "João " : "Maria ") + i;
			String normalized = (accented ? "joao " : "maria ") + i;
			rows.add(new Object[]{name, normalized, "%011d".formatted(i), "seed" + i + "@example.com"});
		}
		jdbcTemplate.batchUpdate("INSERT INTO customers (name, name_normalized, cpf, email, status, created_at, updated_at)"
				+ " VALUES (?, ?, ?, ?, 'ACTIVE', now(), now())", rows);
		return jdbcTemplate.queryForList("SELECT id FROM customers ORDER BY id", Long.class);
	}

	private Duration timed(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
		long start = System.nanoTime();
		int status = mockMvc.perform(request).andReturn().getResponse().getStatus();
		Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
		assertThat(status).isEqualTo(expectedStatus);
		return elapsed;
	}

	private long randomId(List<Long> ids) {
		return ids.get(random.nextInt(ids.size()));
	}

	private static Duration p95(List<Duration> latencies) {
		List<Duration> sorted = latencies.stream().sorted().toList();
		int index = (int) Math.ceil(P95 * sorted.size()) - 1;
		return sorted.get(index);
	}

	/** Builds a valid CPF from a 9-digit base by computing both modulo-11 check digits. */
	private static String validCpf(int base) {
		String digits = "%09d".formatted(base);
		digits += checkDigit(digits);
		digits += checkDigit(digits);
		return digits;
	}

	private static int checkDigit(String digits) {
		int sum = 0;
		for (int i = 0; i < digits.length(); i++)
			sum += (digits.charAt(i) - '0') * (digits.length() + 1 - i);
		int remainder = sum % 11;
		return remainder < 2 ? 0 : 11 - remainder;
	}

	private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
		return request.contentType(MediaType.APPLICATION_JSON).content(body).with(role("ADMIN"));
	}

	private static RequestPostProcessor role(String role) {
		return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
	}
}
