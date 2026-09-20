package com.rekordo.controller;

import com.rekordo.model.exception.BaseException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Ordered ahead of Spring Boot's own advice on purpose.
 *
 * <p>{@code spring.mvc.problemdetails.enabled} registers a handler at order 0 that answers
 * every framework-raised error, validation included. An unordered {@code @RestControllerAdvice}
 * sits at {@link Ordered#LOWEST_PRECEDENCE}, so without this the handlers below are never
 * consulted for the exceptions Boot also knows about — and the stock "Invalid request
 * content." goes out regardless of what is written here.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log4xx = LoggerFactory.getLogger("log4xx");
    private static final Logger log5xx = LoggerFactory.getLogger("log5xx");

    @ExceptionHandler(BaseException.class)
    public ProblemDetail handle(BaseException ex) {
        if (ex.getStatus().is5xxServerError()) {
            log5xx.error("{} — {}", ex.getStatus(), ex.getDetail(), ex);
        } else {
            log4xx.warn("{} — {}", ex.getStatus(), ex.getDetail());
        }
        return ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getDetail());
    }

    /**
     * A request body that failed its constraints — a password below the minimum length, a
     * missing consent tick, an address that is not one.
     *
     * <p>Without this, Spring answers every one of them with its stock {@code "Invalid
     * request content."}, which tells a caller that something in the body was wrong but
     * never which field or why. That is the same answer for a short password and a
     * malformed e-mail, so no client can say anything useful and neither can the person
     * looking at the form.
     *
     * <p>The offending fields are returned twice on purpose: as a readable {@code detail}
     * for whoever is reading the response by hand, and as an {@code errors} map keyed by
     * field name, which is what an app needs to put the message beside the right input in
     * its own language rather than printing the server's English.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handle(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            // First message per field: a field can break several constraints at once
            // (blank *and* too short), and one sentence per input is what a form can show.
            errors.putIfAbsent(error.getField(), messageOf(error.getDefaultMessage()));
        }
        ex.getBindingResult()
                .getGlobalErrors()
                .forEach(error ->
                        errors.putIfAbsent(error.getObjectName(), messageOf(error.getDefaultMessage())));
        return badRequest(errors);
    }

    /**
     * The same failure for values validated on the method signature — query and path
     * parameters — which Spring reports separately from a body.
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ProblemDetail handle(HandlerMethodValidationException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getParameterValidationResults().forEach(result -> {
            String name = result.getMethodParameter().getParameterName();
            result.getResolvableErrors().stream()
                    .findFirst()
                    .ifPresent(error -> errors.putIfAbsent(
                            name == null ? "request" : name, messageOf(error.getDefaultMessage())));
        });
        return badRequest(errors);
    }

    /**
     * Raised by {@code @Validated} on query and path parameters — a malformed barcode, a
     * blank search term. A client mistake, so it is logged as one.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handle(ConstraintViolationException ex) {
        String detail = ex.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                .collect(Collectors.joining("; "));
        log4xx.warn("400 — {}", detail);
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }

    /** A body that is not JSON at all, or whose types do not fit the record it is read into. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handle(HttpMessageNotReadableException ex) {
        log4xx.warn("400 — unreadable request body: {}", ex.getMostSpecificCause().getMessage());
        // Deliberately not the parser's message: it quotes the body back, which in these
        // endpoints can mean quoting a password into a response and a log line.
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "The request body is not valid JSON for this endpoint.");
    }

    private ProblemDetail badRequest(Map<String, String> errors) {
        String detail = errors.isEmpty()
                ? "The request was rejected by validation."
                : errors.entrySet().stream()
                        .map(entry -> entry.getKey() + ": " + entry.getValue())
                        .collect(Collectors.joining("; "));
        log4xx.warn("400 — {}", detail);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        if (!errors.isEmpty()) {
            problem.setProperty("errors", errors);
        }
        return problem;
    }

    private static String messageOf(String defaultMessage) {
        return defaultMessage == null || defaultMessage.isBlank() ? "is not valid" : defaultMessage;
    }
}
