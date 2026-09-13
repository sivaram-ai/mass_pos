package com.masspos.web;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.masspos.auth.AccessDeniedException;
import com.masspos.auth.AuthException;
import com.masspos.hardware.PrinterException;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Every refusal becomes a {@link ProblemDetail} whose {@code detail} is fit to show a cashier as it
 * is. Form refusals also carry {@code errors}, one message per field path (e.g. {@code address[1]}),
 * so a screen can mark each broken input instead of showing a bare "Bad Request".
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** Constraints whose message is already a whole sentence, so no field label goes in front. */
    private static final String ASSERT_TRUE = "AssertTrue";
    private static final Pattern UNIQUE_COLUMN = Pattern.compile("UNIQUE constraint failed: \\w+\\.(\\w+)");

    /** The sale itself is already committed; the UI offers a reprint. */
    @ExceptionHandler(PrinterException.class)
    public ProblemDetail printerUnavailable(PrinterException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        problem.setTitle("Printer unavailable");
        return problem;
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ProblemDetail notFound(EntityNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(AuthException.class)
    public ProblemDetail unauthenticated(AuthException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
        problem.setTitle("Sign in required");
        return problem;
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail forbidden(AccessDeniedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
        problem.setTitle("Not allowed for this role");
        return problem;
    }

    /** Bad input that bean validation cannot express, e.g. a PIN that is too easy or a duplicate username. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** A refused field that only a service can check, e.g. a barcode already on another item. */
    @ExceptionHandler(FieldRejectedException.class)
    public ProblemDetail fieldRejected(FieldRejectedException e) {
        return invalid(Map.of(e.field(), e.getMessage()), Map.of());
    }

    /**
     * The till is not in a state to do this yet, e.g. billing before the shop's name and address are
     * configured. Distinct from 400: the request was fine, the setup is not.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail notReady(IllegalStateException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Not configured yet");
        return problem;
    }

    /** {@code @Valid @RequestBody} refused: the settings form, a product, a sale. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail invalidBody(MethodArgumentNotValidException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        Map<String, String> sentences = new LinkedHashMap<>();
        for (FieldError error : e.getBindingResult().getFieldErrors()) {
            collect(error.getField(), error.getDefaultMessage(), error.getCode(), errors, sentences);
        }
        e.getBindingResult().getGlobalErrors().forEach(error ->
                collect(error.getObjectName(), error.getDefaultMessage(), ASSERT_TRUE, errors, sentences));
        return invalid(errors, sentences);
    }

    /** Constraints on plain parameters, e.g. a {@code @Min} request parameter next to a body. */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ProblemDetail invalidParameters(HandlerMethodValidationException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        Map<String, String> sentences = new LinkedHashMap<>();
        e.getParameterValidationResults().forEach(result -> {
            if (result instanceof ParameterErrors bean) {
                bean.getFieldErrors().forEach(error ->
                        collect(error.getField(), error.getDefaultMessage(), error.getCode(), errors, sentences));
            } else {
                String name = result.getMethodParameter().getParameterName();
                for (MessageSourceResolvable error : result.getResolvableErrors()) {
                    collect(name == null ? "value" : name, error.getDefaultMessage(), null, errors, sentences);
                }
            }
        });
        return invalid(errors, sentences);
    }

    /** An entity refused at flush time, e.g. a selling price above the MRP. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail invalidEntity(ConstraintViolationException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        Map<String, String> sentences = new LinkedHashMap<>();
        for (ConstraintViolation<?> violation : e.getConstraintViolations()) {
            String constraint = violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
            collect(violation.getPropertyPath().toString(), violation.getMessage(), constraint, errors, sentences);
        }
        return invalid(errors, sentences);
    }

    /** Commit-time failures arrive wrapped; entity validation is the one a user can fix. */
    @ExceptionHandler(TransactionSystemException.class)
    public ProblemDetail commitFailed(TransactionSystemException e) {
        Throwable cause = e.getMostSpecificCause();
        if (cause instanceof ConstraintViolationException violations) {
            return invalidEntity(violations);
        }
        log.error("Could not commit", e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Could not save: " + cause.getMessage());
    }

    /** Malformed JSON, or a value of the wrong type such as text in a number or an unknown unit. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail unreadable(HttpMessageNotReadableException e) {
        if (e.getCause() instanceof JsonMappingException mapping && !mapping.getPath().isEmpty()) {
            String field = mapping.getPath().stream()
                    .map(ref -> ref.getFieldName() != null ? "." + ref.getFieldName() : "[" + ref.getIndex() + "]")
                    .collect(Collectors.joining())
                    .replaceFirst("^\\.", "");
            String message = mapping instanceof InvalidFormatException format && format.getTargetType().isEnum()
                    ? "must be one of " + Arrays.toString(format.getTargetType().getEnumConstants())
                    : "has a value of the wrong kind";
            return invalid(Map.of(field, message), Map.of());
        }
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "The request could not be read. Reload the page and try again.");
    }

    /**
     * A database constraint the checks above did not catch, e.g. two tills saving the same item code
     * at once. Named after the column, never a stack trace.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail conflict(DataIntegrityViolationException e) {
        String cause = String.valueOf(e.getMostSpecificCause().getMessage());
        Matcher unique = UNIQUE_COLUMN.matcher(cause);
        String detail = unique.find()
                ? FieldLabels.of(toCamelCase(unique.group(1))) + " is already used by another record"
                : "This change conflicts with data already saved";
        log.warn("Refused by the database: {}", cause);
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detail);
    }

    private static void collect(String field, String message, String constraint, Map<String, String> errors,
                                Map<String, String> sentences) {
        String text = message == null || message.isBlank() ? "is not valid" : message;
        errors.putIfAbsent(field, text);
        sentences.putIfAbsent(field, ASSERT_TRUE.equals(constraint)
                ? Character.toUpperCase(text.charAt(0)) + text.substring(1)
                : FieldLabels.of(field) + " " + text);
    }

    private static ProblemDetail invalid(Map<String, String> errors, Map<String, String> sentences) {
        String detail = errors.keySet().stream()
                .map(field -> sentences.getOrDefault(field, FieldLabels.of(field) + " " + errors.get(field)))
                .collect(Collectors.joining(". ", "", "."));
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("Please correct the highlighted fields");
        problem.setProperty("errors", errors);
        return problem;
    }

    private static String toCamelCase(String column) {
        StringBuilder camel = new StringBuilder();
        boolean upper = false;
        for (char c : column.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                camel.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return camel.toString();
    }
}
