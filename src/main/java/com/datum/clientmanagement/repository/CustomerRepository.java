package com.datum.clientmanagement.repository;

import com.datum.clientmanagement.model.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerRepository extends JpaRepository<Customer, Long>, JpaSpecificationExecutor<Customer> {

	boolean existsByCpf(String cpf);

	/** Deletes in one statement, without loading the entity or checking its version, so racing deletes never fail. */
	@Modifying
	@Query("DELETE FROM Customer customer WHERE customer.id = :id")
	void deleteCustomerById(@Param("id") long id);
}
