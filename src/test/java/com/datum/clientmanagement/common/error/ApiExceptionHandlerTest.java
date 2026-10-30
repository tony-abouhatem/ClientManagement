package com.datum.clientmanagement.common.error;

import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {

    private static final int PAGE_PARAMETER = 0;
    private static final int SIZE_PARAMETER = 1;

    private static final String OBJECT_NAME = "createCustomerRequest";

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void fieldErrors_oneEntryPerFieldSortedByField() {
        List<FieldValidationError> errors = handler.fieldErrors(List.of(
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
    void parameterErrors_namedByRequestParameterAndSortedByName() throws Exception {
        List<FieldValidationError> errors = handler.parameterErrors(List.of(
                parameterResult(SIZE_PARAMETER, 0, "must be between 1 and 100"),
                parameterResult(PAGE_PARAMETER, -1, "must be greater than or equal to 0", null)));

        assertThat(errors).containsExactly(
                new FieldValidationError("page", "is invalid; must be greater than or equal to 0"),
                new FieldValidationError("size", "must be between 1 and 100"));
    }

    @Test
    void responseStatusException_becomesProblemWithStandardTitleAndReasonAsDetail() throws Exception {
        ResponseEntity<Object> response = handler.handleException(
                new ResponseStatusException(HttpStatus.CONFLICT, "A customer with this CPF already exists."),
                new ServletWebRequest(new MockHttpServletRequest()));

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ProblemDetail problem = (ProblemDetail) response.getBody();
        assertProblem(problem, 409, "Conflict");
        assertThat(problem.getDetail()).isEqualTo("A customer with this CPF already exists.");
    }

    @Test
    void unexpectedException_is500WithGenericDetailOnly() {
        ProblemDetail problem = handler.handleUnexpected(new IllegalStateException("secret SQL detail"));

        assertProblem(problem, 500, "Internal Server Error");
        assertThat(problem.getDetail()).isEqualTo(ApiExceptionHandler.UNEXPECTED_ERROR_DETAIL);
    }

    /** A validation result for a parameter of {@link #listing}; a {@code null} message has no default text. */
    private static ParameterValidationResult parameterResult(int index, Object argument, String... messages)
            throws NoSuchMethodException {
        Method method = ApiExceptionHandlerTest.class.getDeclaredMethod("listing", int.class, int.class);
        MethodParameter parameter = new MethodParameter(method, index);
        parameter.initParameterNameDiscovery(new DefaultParameterNameDiscoverer());
        List<MessageSourceResolvable> errors = Arrays.stream(messages)
                .map(message -> (MessageSourceResolvable) new DefaultMessageSourceResolvable(null, null, message))
                .toList();
        return new ParameterValidationResult(parameter, argument, errors, null, null, null, (error, type) -> null);
    }

    @SuppressWarnings("unused") // only its parameter names are used, as the source of parameterResult()
    private void listing(int page, int size) {
    }

    private static void assertProblem(ProblemDetail problem, int status, String title) {
        assertThat(problem.getStatus()).isEqualTo(status);
        assertThat(problem.getTitle()).isEqualTo(title);
        assertThat(problem.getDetail()).isNotBlank();
    }
}
