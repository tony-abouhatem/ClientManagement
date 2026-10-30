package com.datum.clientmanagement.common.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Maps every error raised in Spring MVC to an RFC 7807 ProblemDetail (NFR-13). Framework errors (malformed JSON,
 * type mismatch, unsupported method, ...) and {@link org.springframework.web.server.ResponseStatusException}s
 * thrown by the application come from {@link ResponseEntityExceptionHandler}; validation errors get an
 * {@code errors[]} list and unexpected failures a generic 500 below. Details are fixed texts, so no internals leak
 * into responses.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String ERRORS_PROPERTY = "errors";
    private static final String VALIDATION_DETAIL = "One or more fields are invalid.";
    static final String UNEXPECTED_ERROR_DETAIL = "An unexpected error occurred.";

    private final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String MESSAGE_SEPARATOR = "; ";
    private static final String FALLBACK_FIELD_MESSAGE = "is invalid";

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception exception) {
        log.error("Unexpected error while handling the request", exception);
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, UNEXPECTED_ERROR_DETAIL);
        problem.setTitle(status.getReasonPhrase());
        return problem;
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, VALIDATION_DETAIL);
        problem.setProperty(ERRORS_PROPERTY, fieldErrors(exception.getBindingResult().getFieldErrors()));
        return handleExceptionInternal(exception, problem, headers, status, request);
    }

    /** Invalid query parameters (e.g. {@code size=0}, NFR-14): {@code errors[]} names each parameter. */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, VALIDATION_DETAIL);
        problem.setProperty(ERRORS_PROPERTY, parameterErrors(exception.getParameterValidationResults()));
        return handleExceptionInternal(exception, problem, headers, status, request);
    }

    /** One entry per invalid field (AC-4), sorted by field; several broken rules on a field share one message. */
    List<FieldValidationError> fieldErrors(List<FieldError> errors) {
        return groupedByField(errors.stream()
                .map(error -> new FieldValidationError(error.getField(), messageOf(error))));
    }

    /** Same shape as {@link #fieldErrors}, with the request parameter name as the field. */
    List<FieldValidationError> parameterErrors(List<ParameterValidationResult> results) {
        return groupedByField(results.stream().flatMap(result -> result.getResolvableErrors().stream()
                .map(error -> new FieldValidationError(result.getMethodParameter().getParameterName(),
                        messageOf(error)))));
    }

    private List<FieldValidationError> groupedByField(Stream<FieldValidationError> errors) {
        Map<String, TreeSet<String>> messagesByField = errors.collect(Collectors.groupingBy(
                FieldValidationError::field, TreeMap::new,
                Collectors.mapping(FieldValidationError::message, Collectors.toCollection(TreeSet::new))));
        return messagesByField.entrySet().stream()
                .map(entry -> new FieldValidationError(entry.getKey(), String.join(MESSAGE_SEPARATOR, entry.getValue())))
                .toList();
    }

    private String messageOf(MessageSourceResolvable error) {
        String message = error.getDefaultMessage();
        return message == null ? FALLBACK_FIELD_MESSAGE : message;
    }
}
