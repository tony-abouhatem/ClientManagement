package com.datum.clientmanagement.customer;

import com.datum.clientmanagement.config.PaginationProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import org.hibernate.validator.constraints.Range;
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
@RequestMapping(CustomerController.BASE_PATH)
class CustomerController {

    static final String BASE_PATH = "/customers";

    private final CustomerService customerService;
    private final PaginationProperties pagination;

    CustomerController(CustomerService customerService, PaginationProperties pagination) {
        this.customerService = customerService;
        this.pagination = pagination;
    }

    @PostMapping
    ResponseEntity<CustomerResponse> create(@Valid @RequestBody CustomerRequests.Create request) {
        CustomerResponse created = CustomerResponse.from(customerService.create(request));
        return ResponseEntity.created(URI.create(BASE_PATH + "/" + created.id())).body(created);
    }

    @GetMapping
    PagedModel<CustomerResponse> list(
            @RequestParam(required = false)
            @Pattern(regexp = CustomerStatus.PATTERN, message = CustomerStatus.INVALID_MESSAGE) String status,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) @PositiveOrZero Integer page,
            @RequestParam(required = false)
            @Range(min = PaginationProperties.MIN_SIZE, max = PaginationProperties.MAX_SIZE) Integer size,
            Sort sort) {
        return find(CustomerFilter.fromQuery(status, name), page, size, sort);
    }

    @GetMapping("/search")
    PagedModel<CustomerResponse> search(
            // Not required at binding level, so a missing name is reported in errors[] just like a blank one.
            @RequestParam(required = false) @NotBlank String name,
            @RequestParam(required = false) @PositiveOrZero Integer page,
            @RequestParam(required = false)
            @Range(min = PaginationProperties.MIN_SIZE, max = PaginationProperties.MAX_SIZE) Integer size,
            Sort sort) {
        return find(CustomerFilter.fromQuery(null, name), page, size, sort);
    }

    @GetMapping("/{id}")
    CustomerResponse findById(@PathVariable long id) {
        return CustomerResponse.from(customerService.findById(id));
    }

    @PutMapping("/{id}")
    CustomerResponse update(@PathVariable long id, @Valid @RequestBody CustomerRequests.Update request) {
        return CustomerResponse.from(customerService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable long id) {
        customerService.delete(id);
    }

    private PagedModel<CustomerResponse> find(CustomerFilter filter, Integer page, Integer size, Sort sort) {
        PageRequest pageRequest = PageRequest.of(pagination.pageOrDefault(page), pagination.sizeOrDefault(size),
                CustomerSort.validated(sort));
        return new PagedModel<>(customerService.find(filter, pageRequest).map(CustomerResponse::from));
    }
}
