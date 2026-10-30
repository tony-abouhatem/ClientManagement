package com.datum.clientmanagement.service;

import com.datum.clientmanagement.TestcontainersConfiguration;
import com.datum.clientmanagement.dto.CustomerRequests;
import com.datum.clientmanagement.model.entity.Customer;
import com.datum.clientmanagement.repository.CustomerRepository;
import jakarta.persistence.EntityManager;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

/**
 * Simulates two requests racing past the service's up-front checks, so only PostgreSQL stops the second one
 * (unique CPF constraint, version column in the UPDATE). Proves the real database errors become 409 responses.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"}) // IDE has no database; the SQL runs against Testcontainers Postgres
class CustomerServiceDatabaseGuardTest {

	private static final String CPF = "52998224725";

	@Autowired
	private CustomerService customerService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	// The service transaction's persistence context, used to load the row the way the repository would.
	@Autowired
	private EntityManager entityManager;

	@MockitoSpyBean
	private CustomerRepository customerRepository;

	@BeforeEach
	void deleteAllCustomers() {
		jdbcTemplate.update("DELETE FROM customers");
	}

	@Test
	void create_duplicateCpfCaughtOnlyByDatabase_throwsDuplicateCpf() {
		doReturn(false).when(customerRepository).existsByCpf(anyString());
		customerService.create(new CustomerRequests.Create("Ana Souza", CPF, "ana@example.com", null));

		assertResponseStatus(() -> customerService.create(
				new CustomerRequests.Create("Outra Pessoa", CPF, "outra@example.com", null)),
				CustomerService.DUPLICATE_CPF_MESSAGE);
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM customers WHERE cpf = ?", Integer.class, CPF))
				.isEqualTo(1);
	}

	@Test
	void update_rowChangedAfterVersionCheck_throwsVersionConflict() {
		long id = customerService.create(new CustomerRequests.Create("Ana Souza", CPF, "ana@example.com", null)).getId();
		// Another writer bumps the row right after it was loaded, so only the version in the UPDATE's WHERE catches it.
		doAnswer(invocation -> {
			Optional<Customer> loaded = Optional.ofNullable(entityManager.find(Customer.class, id));
			jdbcTemplate.update("UPDATE customers SET name = 'Other Writer', version = version + 1 WHERE id = ?", id);
			return loaded;
		}).when(customerRepository).findById(id);

		assertResponseStatus(() -> customerService.update(id,
				new CustomerRequests.Update("Name A", "ana@example.com", "ACTIVE", 0L, null)),
				CustomerService.VERSION_CONFLICT_MESSAGE.formatted(id));
		assertThat(jdbcTemplate.queryForObject("SELECT name FROM customers WHERE id = ?", String.class, id))
				.isNotEqualTo("Name A");
	}

	private static void assertResponseStatus(ThrowingCallable call, String reason) {
		assertThatThrownBy(call).isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
			assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
			assertThat(exception.getReason()).isEqualTo(reason);
		});
	}
}
