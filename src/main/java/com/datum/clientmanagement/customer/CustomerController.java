package com.datum.clientmanagement.customer;

import com.datum.clientmanagement.config.PaginationProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import org.hibernate.validator.constraints.Range;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@Tag(name = "Customers")
@ApiResponse(responseCode = "401", description = CustomerController.UNAUTHORIZED)
@RequestMapping(CustomerController.BASE_PATH)
class CustomerController {

    static final String BASE_PATH = "/customers";

    static final String UNAUTHORIZED = "Missing, expired or invalid access token";
    private final String FORBIDDEN = "The token lacks the ADMIN role";
    private static final String INVALID_FIELDS = "Invalid fields; errors[] names each one";
    private static final String INVALID_QUERY = "Invalid status, page, size or sort; errors[] names each parameter";
    private static final String CUSTOMER_NOT_FOUND = "No customer exists with this id";

    private final CustomerService customerService;
    private final PaginationProperties pagination;
    private final CustomerSort customerSort;

    CustomerController(CustomerService customerService, PaginationProperties pagination, CustomerSort customerSort) {
        this.customerService = customerService;
        this.pagination = pagination;
        this.customerSort = customerSort;
    }

    @PostMapping
    @Operation(summary = "Create a customer",
            description = "Requires ADMIN. The CPF may be formatted; it is stored as 11 digits.")
    @ApiResponse(responseCode = "201", description = "Customer created; Location points to it")
    @ApiResponse(responseCode = "400", description = INVALID_FIELDS)
    @ApiResponse(responseCode = "403", description = FORBIDDEN)
    @ApiResponse(responseCode = "409", description = "A customer with this CPF already exists")
    ResponseEntity<CustomerResponse> create(@Valid @RequestBody CustomerRequests.Create request) {
        CustomerResponse created = CustomerResponse.from(customerService.create(request));
        return ResponseEntity.created(URI.create(BASE_PATH + "/" + created.id())).body(created);
    }

    @GetMapping
    @Operation(summary = "List customers",
            description = "Paginated (defaults page 0, size 20; size 1..100), optionally filtered by status and name.")
    @ApiResponse(responseCode = "200", description = "A page of customers")
    @ApiResponse(responseCode = "400", description = INVALID_QUERY)
    PagedModel<CustomerResponse> list(
            @RequestParam(required = false)
            @Pattern(regexp = CustomerStatus.PATTERN, message = CustomerStatus.INVALID_MESSAGE) String status,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) @PositiveOrZero Integer page,
            @RequestParam(required = false)
            @Range(min = PaginationProperties.MIN_SIZE, max = PaginationProperties.MAX_SIZE) Integer size,
            @ParameterObject Sort sort) {
        return find(CustomerFilter.fromQuery(status, name), page, size, sort);
    }

    @GetMapping("/search")
    @Operation(summary = "Search customers by name",
            description = "Case- and accent-insensitive: joao matches João da Silva.")
    @ApiResponse(responseCode = "200", description = "A page of matching customers")
    @ApiResponse(responseCode = "400", description = "Missing or blank name, or invalid page, size or sort")
    PagedModel<CustomerResponse> search(
            // Not required at binding level, so a missing name is reported in errors[] just like a blank one.
            @RequestParam(required = false) @NotBlank String name,
            @RequestParam(required = false) @PositiveOrZero Integer page,
            @RequestParam(required = false)
            @Range(min = PaginationProperties.MIN_SIZE, max = PaginationProperties.MAX_SIZE) Integer size,
            @ParameterObject Sort sort) {
        return find(CustomerFilter.fromQuery(null, name), page, size, sort);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a customer by id")
    @ApiResponse(responseCode = "200", description = "The customer")
    @ApiResponse(responseCode = "404", description = CUSTOMER_NOT_FOUND)
    CustomerResponse findById(@PathVariable long id) {
        return CustomerResponse.from(customerService.findById(id));
    }

    @GetMapping("/{id}/score")
    @Operation(summary = "Get the customer's credit score",
            description = "Looked up in the external Score service by the customer's CPF.")
    @ApiResponse(responseCode = "200", description = "The customer's score")
    @ApiResponse(responseCode = "404",
            description = "Unknown customer (title Not Found) or no score for the CPF (title Score not found)")
    @ApiResponse(responseCode = "502", description = "Score returned an invalid response")
    @ApiResponse(responseCode = "503",
            description = "Score is unavailable (connection failure, 5xx after retries or circuit open)")
    @ApiResponse(responseCode = "504", description = "Score did not answer within the read timeout")
    ScoreResponse score(@PathVariable long id) {
        return customerService.scoreOf(id);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a customer",
            description = "Requires ADMIN and the version last read (optimistic locking). The CPF cannot change.")
    @ApiResponse(responseCode = "200", description = "Customer updated, with the new version")
    @ApiResponse(responseCode = "400", description = "Invalid fields or missing version; errors[] names each one")
    @ApiResponse(responseCode = "403", description = FORBIDDEN)
    @ApiResponse(responseCode = "404", description = CUSTOMER_NOT_FOUND)
    @ApiResponse(responseCode = "409", description = "The version does not match the stored one")
    @ApiResponse(responseCode = "422", description = "The request tries to change the CPF")
    CustomerResponse update(@PathVariable long id, @Valid @RequestBody CustomerRequests.Update request) {
        return CustomerResponse.from(customerService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a customer",
            description = "Requires ADMIN. Idempotent: an unknown or already-deleted id also returns 204.")
    @ApiResponse(responseCode = "204", description = "Customer deleted, or it did not exist")
    @ApiResponse(responseCode = "403", description = FORBIDDEN)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable long id) {
        customerService.delete(id);
    }

    private PagedModel<CustomerResponse> find(CustomerFilter filter, Integer page, Integer size, Sort sort) {
        PageRequest pageRequest = PageRequest.of(pagination.pageOrDefault(page), pagination.sizeOrDefault(size),
                customerSort.validated(sort));
        return new PagedModel<>(customerService.find(filter, pageRequest).map(CustomerResponse::from));
    }
}
