package com.datum.clientmanagement.common.error;

import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {

    private static final String OBJECT_NAME = "createCustomerRequest";

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void fieldErrors_oneEntryPerFieldSortedByField() {
        List<FieldValidationError> errors = ApiExceptionHandler.fieldErrors(List.of(
                new FieldError(OBJECT_NAME, "name", "must not be blank"),
                new FieldError(OBJECT_NAME, "email", "must be a well-formed email address"),
                new FieldError(OBJECT_NAME, "email", "size must be between 0 and 254"),
                new FieldError(OBJECT_NAME, "cpf", null)));

        assertThat(errors).containsExactly(
                new FieldValidationError("cpf", "is invalid"),
                new FieldValidationError("email", "must be a well-formed email address; size must be between 0 and 254"),
                new FieldValidationError("name", "must not be blank"));
    }

    @Test
    void customerNotFound_is404WithFixedTitle() {
        ProblemDetail problem = handler.handleCustomerNotFound(new CustomerNotFoundException(42));

        assertProblem(problem, 404, "Customer not found");
        assertThat(problem.getDetail()).contains("42");
    }

    @Test
    void domainConflicts_mapTo409And422() {
        assertProblem(handler.handleDuplicateCpf(new DuplicateCpfException()), 409, "Duplicate CPF");
        assertProblem(handler.handleVersionConflict(new CustomerVersionConflictException(1)), 409, "Version conflict");
        assertProblem(handler.handleCpfChange(new CpfChangeNotAllowedException()), 422, "CPF cannot be changed");
    }

    @Test
    void unexpectedException_is500WithGenericDetailOnly() {
        ProblemDetail problem = handler.handleUnexpected(new IllegalStateException("secret SQL detail"));

        assertProblem(problem, 500, "Internal Server Error");
        assertThat(problem.getDetail()).isEqualTo(ApiExceptionHandler.UNEXPECTED_ERROR_DETAIL);
    }

    private static void assertProblem(ProblemDetail problem, int status, String title) {
        assertThat(problem.getStatus()).isEqualTo(status);
        assertThat(problem.getTitle()).isEqualTo(title);
        assertThat(problem.getDetail()).isNotBlank();
    }
}
