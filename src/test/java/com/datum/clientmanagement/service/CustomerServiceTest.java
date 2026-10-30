package com.datum.clientmanagement.service;

import com.datum.clientmanagement.dto.CustomerCreatedEvent;
import com.datum.clientmanagement.dto.CustomerFilter;
import com.datum.clientmanagement.dto.CustomerRequest;
import com.datum.clientmanagement.dto.ExternalScore;
import com.datum.clientmanagement.dto.ScoreResponse;
import com.datum.clientmanagement.model.Customer;
import com.datum.clientmanagement.model.CustomerStatus;
import com.datum.clientmanagement.repository.CustomerRepository;
import com.datum.clientmanagement.repository.CustomerSpecifications;
import com.datum.clientmanagement.util.CustomerFormats;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CustomerServiceTest {

	private static final long CUSTOMER_ID = 7L;
	private static final String CPF = "52998224725";
	private static final Instant CREATED_AT = Instant.parse("2026-01-01T10:00:00.123456Z");
	// Nanosecond precision on purpose: the service must truncate to what PostgreSQL stores (microseconds).
	private static final Instant NOW = Instant.parse("2026-02-01T12:30:00.123456789Z");
	private static final Instant NOW_TRUNCATED = Instant.parse("2026-02-01T12:30:00.123456Z");

	private final CustomerRepository repository = mock(CustomerRepository.class);
	private final ScoreClient scoreClient = mock(ScoreClient.class);
	private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
	private final CustomerService service =
			new CustomerService(repository, Clock.fixed(NOW, ZoneOffset.UTC), scoreClient, events,
					new CustomerSpecifications(), new CustomerFormats());

	@Test
	void create_newCpf_savesNormalizedCustomerWithDefaults() {
		savesWithGeneratedId();

		Customer created = service.create(new CustomerRequest("  João da Silva ", "529.982.247-25",
				"joao@example.com", null, null));

		ArgumentCaptor<Customer> saved = ArgumentCaptor.forClass(Customer.class);
		verify(repository).saveAndFlush(saved.capture());
		assertThat(saved.getValue()).isSameAs(created);
		assertThat(created.getName()).isEqualTo("João da Silva");
		assertThat(created.getNameNormalized()).isEqualTo("joao da silva");
		assertThat(created.getCpf()).isEqualTo(CPF);
		assertThat(created.getEmail()).isEqualTo("joao@example.com");
		assertThat(created.getStatus()).isEqualTo(CustomerStatus.ACTIVE);
		assertThat(created.getCreatedAt()).isEqualTo(NOW_TRUNCATED);
		assertThat(created.getUpdatedAt()).isEqualTo(NOW_TRUNCATED);
	}

	@Test
	void create_newCpf_raisesCustomerCreatedEventWithSavedIdAndCreatedAt() {
		savesWithGeneratedId();

		service.create(createRequest());

		ArgumentCaptor<CustomerCreatedEvent> raised = ArgumentCaptor.forClass(CustomerCreatedEvent.class);
		verify(events).publishEvent(raised.capture());
		assertThat(raised.getValue().customerId()).isEqualTo(CUSTOMER_ID);
		assertThat(raised.getValue().createdAt()).isEqualTo(NOW_TRUNCATED);
	}

	// Like JPA's identity generation: the saved instance is the same object, now with an id.
	private void savesWithGeneratedId() {
		when(repository.saveAndFlush(any(Customer.class))).thenAnswer(invocation -> {
			Customer customer = invocation.getArgument(0);
			ReflectionTestUtils.setField(customer, "id", CUSTOMER_ID);
			return customer;
		});
	}

	@Test
	void create_existingCpf_throwsDuplicateWithoutSaving() {
		when(repository.existsByCpf(CPF)).thenReturn(true);

		assertResponseStatus(() -> service.create(createRequest()), HttpStatus.CONFLICT,
				CustomerService.DUPLICATE_CPF_MESSAGE);
		assertThat(CustomerService.DUPLICATE_CPF_MESSAGE).doesNotContain(CPF);
		verify(repository, never()).saveAndFlush(any());
		verifyNoInteractions(events);
	}

	@Test
	void create_cpfUniqueViolationFromRace_throwsDuplicate() {
		when(repository.saveAndFlush(any(Customer.class))).thenThrow(integrityViolation(CustomerService.CPF_UNIQUE_CONSTRAINT));

		assertResponseStatus(() -> service.create(createRequest()), HttpStatus.CONFLICT,
				CustomerService.DUPLICATE_CPF_MESSAGE);
		verifyNoInteractions(events);
	}

	@Test
	void create_otherIntegrityViolation_isRethrown() {
		DataIntegrityViolationException otherViolation = integrityViolation("ck_customers_status");
		when(repository.saveAndFlush(any(Customer.class))).thenThrow(otherViolation);

		assertThatThrownBy(() -> service.create(createRequest())).isSameAs(otherViolation);
	}

	@Test
	void findById_unknownId_throwsNotFound() {
		when(repository.findById(CUSTOMER_ID)).thenReturn(Optional.empty());

		assertResponseStatus(() -> service.findById(CUSTOMER_ID), HttpStatus.NOT_FOUND,
				"No customer exists with id " + CUSTOMER_ID + ".");
	}

	@Test
	void scoreOf_existingCustomer_combinesCustomerAndScore() {
		storedCustomer(0);
		when(scoreClient.fetch(CPF)).thenReturn(new ExternalScore("ignored", 750, "GOOD"));

		assertThat(service.scoreOf(CUSTOMER_ID)).isEqualTo(new ScoreResponse(CUSTOMER_ID, CPF, 750, "GOOD"));
	}

	@Test
	void scoreOf_unknownCustomer_throwsNotFoundWithoutCallingScore() {
		when(repository.findById(CUSTOMER_ID)).thenReturn(Optional.empty());

		assertResponseStatus(() -> service.scoreOf(CUSTOMER_ID), HttpStatus.NOT_FOUND,
				"No customer exists with id " + CUSTOMER_ID + ".");
		verify(scoreClient, never()).fetch(ArgumentMatchers.anyString());
	}

	@Test
	void scoreOf_scoreFailure_propagates() {
		storedCustomer(0);
		ResponseStatusException unavailable = new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "down");
		when(scoreClient.fetch(CPF)).thenThrow(unavailable);

		assertThatThrownBy(() -> service.scoreOf(CUSTOMER_ID)).isSameAs(unavailable);
	}

	@Test
	void update_matchingVersion_appliesChangesAndFlushes() {
		Customer stored = storedCustomer(0L);

		Customer updated = service.update(CUSTOMER_ID,
				new CustomerRequest("Élise Março", "529.982.247-25", "elise@example.com", "INACTIVE", 0L));

		assertThat(updated.getName()).isEqualTo("Élise Março");
		assertThat(updated.getNameNormalized()).isEqualTo("elise marco");
		assertThat(updated.getEmail()).isEqualTo("elise@example.com");
		assertThat(updated.getStatus()).isEqualTo(CustomerStatus.INACTIVE);
		assertThat(updated.getCpf()).isEqualTo(CPF);
		assertThat(updated.getCreatedAt()).isEqualTo(CREATED_AT);
		assertThat(updated.getUpdatedAt()).isEqualTo(NOW_TRUNCATED);
		assertThat(updated).isSameAs(stored);
		verify(repository).flush();
	}

	@Test
	void update_staleVersion_throwsConflictAndChangesNothing() {
		Customer stored = storedCustomer(1L);

		assertResponseStatus(() -> service.update(CUSTOMER_ID, updateRequest(null)), HttpStatus.CONFLICT,
				CustomerService.VERSION_CONFLICT_MESSAGE.formatted(CUSTOMER_ID));
		assertUnchanged(stored);
		verify(repository, never()).flush();
	}

	@Test
	void update_differentCpf_throwsCpfChangeAndChangesNothing() {
		Customer stored = storedCustomer(0L);

		assertResponseStatus(() -> service.update(CUSTOMER_ID, updateRequest("11144477735")),
				HttpStatus.UNPROCESSABLE_CONTENT, CustomerService.CPF_CHANGE_MESSAGE);
		assertUnchanged(stored);
		verify(repository, never()).flush();
	}

	@Test
	void update_concurrentModificationOnFlush_throwsConflict() {
		storedCustomer(0L);
		doThrow(new ObjectOptimisticLockingFailureException(Customer.class, CUSTOMER_ID))
				.when(repository).flush();

		assertResponseStatus(() -> service.update(CUSTOMER_ID, updateRequest(null)), HttpStatus.CONFLICT,
				CustomerService.VERSION_CONFLICT_MESSAGE.formatted(CUSTOMER_ID));
	}

	@Test
	void update_unknownId_throwsNotFound() {
		when(repository.findById(CUSTOMER_ID)).thenReturn(Optional.empty());

		assertResponseStatus(() -> service.update(CUSTOMER_ID, updateRequest(null)), HttpStatus.NOT_FOUND,
				"No customer exists with id " + CUSTOMER_ID + ".");
	}

	@Test
	void find_queriesRepositoryWithFilterAndPageRequest() {
		CustomerSpecifications specifications = mock(CustomerSpecifications.class);
		CustomerService serviceWithMockedSpecifications = new CustomerService(repository,
				Clock.fixed(NOW, ZoneOffset.UTC), scoreClient, events, specifications, new CustomerFormats());
		CustomerFilter filter = new CustomerFilter(CustomerStatus.ACTIVE, "joao");
		Specification<Customer> filterSpecification = (root, query, builder) -> null;
		when(specifications.matching(filter)).thenReturn(filterSpecification);
		Pageable pageable = PageRequest.of(1, 20, Sort.by("id"));
		Page<Customer> page = new PageImpl<>(List.of(), pageable, 0);
		when(repository.findAll(filterSpecification, pageable)).thenReturn(page);

		Page<Customer> found = serviceWithMockedSpecifications.find(filter, pageable);

		assertThat(found).isSameAs(page);
		verify(specifications).matching(filter);
		verify(repository).findAll(filterSpecification, pageable);
	}

	@Test
	void changeStatus_differentStatus_setsStatusAndUpdatedAt() {
		Customer customer = storedCustomer(0);

		service.changeStatus(CUSTOMER_ID, CustomerStatus.INACTIVE);

		assertThat(customer.getStatus()).isEqualTo(CustomerStatus.INACTIVE);
		assertThat(customer.getUpdatedAt()).isEqualTo(NOW_TRUNCATED);
	}

	@Test
	void changeStatus_sameStatus_changesNothing() {
		Customer customer = storedCustomer(0);

		service.changeStatus(CUSTOMER_ID, CustomerStatus.ACTIVE);

		assertThat(customer.getStatus()).isEqualTo(CustomerStatus.ACTIVE);
		assertThat(customer.getUpdatedAt()).isEqualTo(CREATED_AT);
	}

	@Test
	void changeStatus_unknownId_throws404() {
		when(repository.findById(CUSTOMER_ID)).thenReturn(Optional.empty());

		assertResponseStatus(() -> service.changeStatus(CUSTOMER_ID, CustomerStatus.INACTIVE), HttpStatus.NOT_FOUND,
				"No customer exists with id " + CUSTOMER_ID + ".");
	}

	@Test
	void delete_deletesById() {
		service.delete(CUSTOMER_ID);

		verify(repository).deleteCustomerById(CUSTOMER_ID);
	}

	private static void assertResponseStatus(ThrowingCallable call, HttpStatus status, String reason) {
		assertThatThrownBy(call).isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
			assertThat(exception.getStatusCode()).isEqualTo(status);
			assertThat(exception.getReason()).isEqualTo(reason);
		});
	}

	private static void assertUnchanged(Customer stored) {
		assertThat(stored.getName()).isEqualTo("Ana Souza");
		assertThat(stored.getNameNormalized()).isEqualTo("ana souza");
		assertThat(stored.getEmail()).isEqualTo("ana@example.com");
		assertThat(stored.getStatus()).isEqualTo(CustomerStatus.ACTIVE);
		assertThat(stored.getCpf()).isEqualTo(CPF);
		assertThat(stored.getUpdatedAt()).isEqualTo(CREATED_AT);
	}

	private Customer storedCustomer(long version) {
		Customer customer = new Customer("Ana Souza", CPF, "ana@example.com", CustomerStatus.ACTIVE, CREATED_AT);
		ReflectionTestUtils.setField(customer, "id", CUSTOMER_ID);
		ReflectionTestUtils.setField(customer, "version", version);
		when(repository.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer));
		return customer;
	}

	private static CustomerRequest createRequest() {
		return new CustomerRequest("Ana Souza", CPF, "ana@example.com", null, null);
	}

	private static CustomerRequest updateRequest(String cpf) {
		return new CustomerRequest("Changed", cpf, "changed@example.com", "INACTIVE", 0L);
	}

	private static DataIntegrityViolationException integrityViolation(String constraintName) {
		SQLException sqlException = new SQLException("duplicate key value", "23505");
		return new DataIntegrityViolationException("could not execute statement",
				new ConstraintViolationException("could not execute statement", sqlException, constraintName));
	}
}
