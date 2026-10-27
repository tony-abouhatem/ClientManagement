package com.datum.clientmanagement.customer;

import com.datum.clientmanagement.common.error.CpfChangeNotAllowedException;
import com.datum.clientmanagement.common.error.CustomerNotFoundException;
import com.datum.clientmanagement.common.error.CustomerVersionConflictException;
import com.datum.clientmanagement.common.error.DuplicateCpfException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomerServiceTest {

    private static final long CUSTOMER_ID = 7L;
    private static final String CPF = "52998224725";
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T10:00:00.123456Z");
    // Nanosecond precision on purpose: the service must truncate to what PostgreSQL stores (microseconds).
    private static final Instant NOW = Instant.parse("2026-02-01T12:30:00.123456789Z");
    private static final Instant NOW_TRUNCATED = Instant.parse("2026-02-01T12:30:00.123456Z");

    private final CustomerRepository repository = mock(CustomerRepository.class);
    private final CustomerService service = new CustomerService(repository, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void create_newCpf_savesNormalizedCustomerWithDefaults() {
        when(repository.saveAndFlush(any(Customer.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Customer created = service.create(new CustomerRequests.Create("  João da Silva ", "529.982.247-25",
                "joao@example.com", null));

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
    void create_existingCpf_throwsDuplicateWithoutSaving() {
        when(repository.existsByCpf(CPF)).thenReturn(true);

        assertThatThrownBy(() -> service.create(createRequest()))
                .isInstanceOf(DuplicateCpfException.class)
                .message().doesNotContain(CPF);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void create_cpfUniqueViolationFromRace_throwsDuplicate() {
        when(repository.saveAndFlush(any(Customer.class))).thenThrow(integrityViolation(CustomerService.CPF_UNIQUE_CONSTRAINT));

        assertThatThrownBy(() -> service.create(createRequest())).isInstanceOf(DuplicateCpfException.class);
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

        assertThatThrownBy(() -> service.findById(CUSTOMER_ID)).isInstanceOf(CustomerNotFoundException.class);
    }

    @Test
    void update_matchingVersion_appliesChangesAndFlushes() {
        Customer stored = storedCustomer(0L);

        Customer updated = service.update(CUSTOMER_ID,
                new CustomerRequests.Update("Élise Março", "elise@example.com", "INACTIVE", 0L, "529.982.247-25"));

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

        assertThatThrownBy(() -> service.update(CUSTOMER_ID, updateRequest(0L, null)))
                .isInstanceOf(CustomerVersionConflictException.class);
        assertThat(stored.getName()).isEqualTo("Ana Souza");
        verify(repository, never()).flush();
    }

    @Test
    void update_differentCpf_throwsCpfChangeAndChangesNothing() {
        Customer stored = storedCustomer(0L);

        assertThatThrownBy(() -> service.update(CUSTOMER_ID, updateRequest(0L, "11144477735")))
                .isInstanceOf(CpfChangeNotAllowedException.class);
        assertThat(stored.getName()).isEqualTo("Ana Souza");
        verify(repository, never()).flush();
    }

    @Test
    void update_concurrentModificationOnFlush_throwsConflict() {
        storedCustomer(0L);
        doThrow(new ObjectOptimisticLockingFailureException(Customer.class, CUSTOMER_ID))
                .when(repository).flush();

        assertThatThrownBy(() -> service.update(CUSTOMER_ID, updateRequest(0L, null)))
                .isInstanceOf(CustomerVersionConflictException.class);
    }

    @Test
    void update_unknownId_throwsNotFound() {
        when(repository.findById(CUSTOMER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(CUSTOMER_ID, updateRequest(0L, null)))
                .isInstanceOf(CustomerNotFoundException.class);
    }

    @Test
    void delete_deletesById() {
        service.delete(CUSTOMER_ID);

        verify(repository).deleteCustomerById(CUSTOMER_ID);
    }

    private Customer storedCustomer(long version) {
        Customer customer = Customer.create("Ana Souza", CPF, "ana@example.com", CustomerStatus.ACTIVE, CREATED_AT);
        ReflectionTestUtils.setField(customer, "id", CUSTOMER_ID);
        ReflectionTestUtils.setField(customer, "version", version);
        when(repository.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer));
        return customer;
    }

    private static CustomerRequests.Create createRequest() {
        return new CustomerRequests.Create("Ana Souza", CPF, "ana@example.com", null);
    }

    private static CustomerRequests.Update updateRequest(long version, String cpf) {
        return new CustomerRequests.Update("Changed", "changed@example.com", "INACTIVE", version, cpf);
    }

    private static DataIntegrityViolationException integrityViolation(String constraintName) {
        SQLException sqlException = new SQLException("duplicate key value", "23505");
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("could not execute statement", sqlException, constraintName));
    }
}
