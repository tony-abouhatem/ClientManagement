package com.datum.clientmanagement.customer;

import com.datum.clientmanagement.common.error.CpfChangeNotAllowedException;
import com.datum.clientmanagement.common.error.CustomerNotFoundException;
import com.datum.clientmanagement.common.error.CustomerVersionConflictException;
import com.datum.clientmanagement.common.error.DuplicateCpfException;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Customer use cases. The database is the final guard for CPF uniqueness and optimistic locking, so races that
 * slip past the up-front checks still end in the same domain exceptions (409) instead of a 500.
 */
@Service
public class CustomerService {

    static final String CPF_UNIQUE_CONSTRAINT = "uk_customers_cpf";

    private static final Logger log = LoggerFactory.getLogger(CustomerService.class);

    private final CustomerRepository repository;
    private final Clock clock;

    public CustomerService(CustomerRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public Customer create(CustomerRequests.Create request) {
        if (repository.existsByCpf(request.cpf())) {
            logDuplicateCpf(request.cpf());
            throw new DuplicateCpfException();
        }
        Customer customer = Customer.create(request.name(), request.cpf(), request.email(), request.statusOrDefault(),
                now());
        try {
            Customer saved = repository.saveAndFlush(customer);
            log.info("Customer {} created with CPF {}", saved.getId(), CustomerFormats.maskCpf(saved.getCpf()));
            return saved;
        } catch (DataIntegrityViolationException exception) {
            if (isCpfUniqueViolation(exception)) {
                logDuplicateCpf(request.cpf());
                throw new DuplicateCpfException(exception);
            }
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public Customer findById(long id) {
        return repository.findById(id).orElseThrow(() -> new CustomerNotFoundException(id));
    }

    @Transactional
    public Customer update(long id, CustomerRequests.Update request) {
        Customer customer = findById(id);
        if (!customer.getVersion().equals(request.version())) {
            throw new CustomerVersionConflictException(id);
        }
        if (request.cpf() != null && !request.cpf().equals(customer.getCpf())) {
            throw new CpfChangeNotAllowedException();
        }
        customer.update(request.name(), request.email(), request.customerStatus(), now());
        try {
            // Flush now so a concurrent update that already bumped the version surfaces here, not at commit.
            repository.flush();
        } catch (OptimisticLockingFailureException exception) {
            throw new CustomerVersionConflictException(id, exception);
        }
        log.info("Customer {} updated to version {}", id, customer.getVersion());
        return customer;
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

    private static void logDuplicateCpf(String cpf) {
        log.info("Rejected customer creation: CPF {} is already registered", CustomerFormats.maskCpf(cpf));
    }

    private static boolean isCpfUniqueViolation(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return CPF_UNIQUE_CONSTRAINT.equalsIgnoreCase(violation.getConstraintName());
            }
        }
        return false;
    }
}
