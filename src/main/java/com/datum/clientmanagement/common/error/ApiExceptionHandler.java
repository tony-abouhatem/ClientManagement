package com.datum.clientmanagement.common.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Maps every error raised in Spring MVC to an RFC 7807 ProblemDetail (NFR-13). Framework errors (malformed JSON,
 * type mismatch, unsupported method, ...) come from {@link ResponseEntityExceptionHandler}; domain errors and
 * unexpected failures are handled below. Details are fixed texts, so no internals leak into responses.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    static final String ERRORS_PROPERTY = "errors";
    static final String VALIDATION_DETAIL = "One or more fields are invalid.";
    static final String CUSTOMER_NOT_FOUND_TITLE = "Customer not found";
    static final String DUPLICATE_CPF_TITLE = "Duplicate CPF";
    static final String VERSION_CONFLICT_TITLE = "Version conflict";
    static final String CPF_CHANGE_TITLE = "CPF cannot be changed";
    static final String UNEXPECTED_ERROR_DETAIL = "An unexpected error occurred.";

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String MESSAGE_SEPARATOR = "; ";
    private static final String FALLBACK_FIELD_MESSAGE = "is invalid";

    @ExceptionHandler(CustomerNotFoundException.class)
    ProblemDetail handleCustomerNotFound(CustomerNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, CUSTOMER_NOT_FOUND_TITLE, exception.getMessage());
    }

    @ExceptionHandler(DuplicateCpfException.class)
    ProblemDetail handleDuplicateCpf(DuplicateCpfException exception) {
        return problem(HttpStatus.CONFLICT, DUPLICATE_CPF_TITLE, exception.getMessage());
    }

    @ExceptionHandler(CustomerVersionConflictException.class)
    ProblemDetail handleVersionConflict(CustomerVersionConflictException exception) {
        return problem(HttpStatus.CONFLICT, VERSION_CONFLICT_TITLE, exception.getMessage());
    }

    @ExceptionHandler(CpfChangeNotAllowedException.class)
    ProblemDetail handleCpfChange(CpfChangeNotAllowedException exception) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, CPF_CHANGE_TITLE, exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception exception) {
        log.error("Unexpected error while handling the request", exception);
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        return problem(status, status.getReasonPhrase(), UNEXPECTED_ERROR_DETAIL);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, VALIDATION_DETAIL);
        problem.setProperty(ERRORS_PROPERTY, fieldErrors(exception.getBindingResult().getFieldErrors()));
        return handleExceptionInternal(exception, problem, headers, status, request);
    }

    /** One entry per invalid field (AC-4), sorted by field; several broken rules on a field share one message. */
    static List<FieldValidationError> fieldErrors(List<FieldError> errors) {
        Map<String, TreeSet<String>> messagesByField = errors.stream().collect(Collectors.groupingBy(
                FieldError::getField, TreeMap::new,
                Collectors.mapping(ApiExceptionHandler::messageOf, Collectors.toCollection(TreeSet::new))));
        return messagesByField.entrySet().stream()
                .map(entry -> new FieldValidationError(entry.getKey(), String.join(MESSAGE_SEPARATOR, entry.getValue())))
                .toList();
    }

    private static String messageOf(FieldError error) {
        String message = error.getDefaultMessage();
        return message == null ? FALLBACK_FIELD_MESSAGE : message;
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
