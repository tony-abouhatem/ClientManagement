package com.datum.clientmanagement.service;

import com.datum.clientmanagement.dto.CustomerFilter;
import com.datum.clientmanagement.dto.CustomerRequests;
import com.datum.clientmanagement.dto.ScoreResponse;
import com.datum.clientmanagement.event.CustomerCreatedEvent;
import com.datum.clientmanagement.model.entity.Customer;
import com.datum.clientmanagement.model.enums.CustomerStatus;
import com.datum.clientmanagement.repository.CustomerRepository;
import com.datum.clientmanagement.repository.CustomerSpecifications;
import com.datum.clientmanagement.util.CustomerFormats;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Customer use cases. Failures are thrown as {@link ResponseStatusException}s whose reason is a client-safe
 * message (never the CPF, NFR-3). The database is the final guard for CPF uniqueness and optimistic locking, so
 * races that slip past the up-front checks still end in the same 409 instead of a 500.
 */
@Service
public class CustomerService {

	static final String CPF_UNIQUE_CONSTRAINT = "uk_customers_cpf";
	static final String DUPLICATE_CPF_MESSAGE = "A customer with this CPF already exists.";
	static final String VERSION_CONFLICT_MESSAGE =
			"Customer %d was modified by another request. Fetch it again and retry with the current version.";
	static final String CPF_CHANGE_MESSAGE = "The CPF of an existing customer cannot be changed.";

	private final Logger log = LoggerFactory.getLogger(CustomerService.class);

	private final CustomerRepository repository;
	private final Clock clock;
	private final ScoreClient scoreClient;
	private final ApplicationEventPublisher events;
	private final CustomerSpecifications specifications;
	private final CustomerFormats formats;

	public CustomerService(CustomerRepository repository, Clock clock, ScoreClient scoreClient,
			ApplicationEventPublisher events, CustomerSpecifications specifications, CustomerFormats formats) {
		this.repository = repository;
		this.clock = clock;
		this.scoreClient = scoreClient;
		this.events = events;
		this.specifications = specifications;
		this.formats = formats;
	}

	@Transactional
	public Customer create(CustomerRequests.Create request) {
		if (repository.existsByCpf(request.cpf())) {
			logDuplicateCpf(request.cpf());
			throw new ResponseStatusException(HttpStatus.CONFLICT, DUPLICATE_CPF_MESSAGE);
		}
		Customer customer = new Customer(request.name(), request.cpf(), request.email(), request.statusOrDefault(),
				now());
		try {
			Customer saved = repository.saveAndFlush(customer);
			log.info("Customer {} created with CPF {}", saved.getId(), formats.maskCpf(saved.getCpf()));
			events.publishEvent(new CustomerCreatedEvent(saved.getId(), saved.getCreatedAt()));
			return saved;
		} catch (DataIntegrityViolationException exception) {
			if (isCpfUniqueViolation(exception)) {
				logDuplicateCpf(request.cpf());
				throw new ResponseStatusException(HttpStatus.CONFLICT, DUPLICATE_CPF_MESSAGE, exception);
			}
			throw exception;
		}
	}

	@Transactional(readOnly = true)
	public Customer findById(long id) {
		return repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
				"No customer exists with id " + id + "."));
	}

	/**
	 * The customer's score from the external Score service, asked by the stored (normalized) CPF. An unknown id
	 * fails with 404 before Score is called. Deliberately not {@code @Transactional}: no database connection is
	 * held while waiting up to 5 s for Score (the repository lookup runs in its own read-only transaction).
	 */
	public ScoreResponse scoreOf(long id) {
		Customer customer = findById(id);
		return ScoreResponse.from(customer, scoreClient.fetch(customer.getCpf()));
	}

	@Transactional(readOnly = true)
	public Page<Customer> find(CustomerFilter filter, Pageable pageable) {
		return repository.findAll(specifications.matching(filter), pageable);
	}

	@Transactional
	public Customer update(long id, CustomerRequests.Update request) {
		Customer customer = findById(id);
		if (!customer.getVersion().equals(request.version()))
			throw new ResponseStatusException(HttpStatus.CONFLICT, VERSION_CONFLICT_MESSAGE.formatted(id));
		if (request.cpf() != null && !request.cpf().equals(customer.getCpf()))
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, CPF_CHANGE_MESSAGE);
		customer.update(request.name(), request.email(), request.customerStatus(), now());
		try {
			// Flush now so a concurrent update that already bumped the version surfaces here, not at commit.
			repository.flush();
		} catch (OptimisticLockingFailureException exception) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, VERSION_CONFLICT_MESSAGE.formatted(id), exception);
		}
		log.info("Customer {} updated to version {}", id, customer.getVersion());
		return customer;
	}

	/**
	 * Status change requested by an upstream system through RabbitMQ (ticket 07). Joins the caller's transaction,
	 * so the change commits together with the caller's idempotency record. Asking for the current status is a
	 * no-op. A concurrent API update surfaces as an {@link OptimisticLockingFailureException} at commit, which the
	 * consumer treats as transient.
	 *
	 * @throws ResponseStatusException with 404 when no customer has this id (same as {@link #findById})
	 */
	@Transactional
	public void changeStatus(long id, CustomerStatus status) {
		Customer customer = findById(id);
		CustomerStatus previous = customer.getStatus();
		customer.changeStatus(status, now());
		log.info("Customer {} status change requested: {} -> {}", id, previous, status);
	}

	@Transactional
	public void delete(long id) {
		// Idempotent: an unknown or already-deleted id is a no-op (still 204).
		repository.deleteCustomerById(id);
		log.info("Delete requested for customer {}", id);
	}

	// PostgreSQL keeps microseconds; truncating makes the response identical to what a later GET returns.
	private Instant now() {
		return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
	}

	private void logDuplicateCpf(String cpf) {
		log.info("Rejected customer creation: CPF {} is already registered", formats.maskCpf(cpf));
	}

	private boolean isCpfUniqueViolation(DataIntegrityViolationException exception) {
		for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation)
				return CPF_UNIQUE_CONSTRAINT.equalsIgnoreCase(violation.getConstraintName());
		}
		return false;
	}
}
