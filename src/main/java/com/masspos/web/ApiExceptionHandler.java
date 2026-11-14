package com.masspos.web;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.masspos.auth.AccessDeniedException;
import com.masspos.auth.AuthException;
import com.masspos.hardware.PrinterException;
import com.masspos.license.LicenseLockedException;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataIntegrityViolationException;
import com.masspos.auth.SessionConflictException;
import com.masspos.client.ClientRefusedException;
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

    /**
     * Wording that came from underneath the shop: package and class names, stack frames, database
     * text, Windows paths, ids and property names. Every one of these has been on a screen at
     * least once.
     */
    private static final Pattern INTERNAL = Pattern.compile(
            "com\\.masspos"
            + "|\\b(java|javax|jakarta)\\.[a-z]+\\."
            + "|\\borg\\.(springframework|hibernate|sqlite|apache)"
            + "|SQLITE|constraint failed|jdbc:"
            + "|\\b[A-Za-z]:\\\\"
            + "|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
            + "|\\bpos\\.[a-z]+\\.[a-z]"
            + "|\\.properties\\b"
            + "|\\bat [a-z]+\\.[a-z]+\\.");

    /**
     * The last gate between the inside of the program and a screen at a counter.
     *
     * <p>Nearly every refusal this class turns into a {@code detail} is a sentence somebody wrote
     * for a shop. But a refusal can also come from a layer nobody here worded - a library, a
     * driver, an invariant only ever meant to fail in a test - and its message is then a class
     * name, a file path, an id or a line of SQL. At a till that is worse than silence: the cashier
     * cannot act on it, cannot repeat it down the phone, and has a customer waiting. The whole
     * failure goes to the log, where whoever is called out will look; the screen gets a sentence.
     *
     * <p>Catching it here rather than at each throw site is deliberate. This is the one place every
     * refusal passes through, so wording that slips in later is caught without anybody having to
     * remember to look for it.
     */
    private static String forTheScreen(Throwable e, String instead) {
        String said = e.getMessage();
        if (said != null && !said.isBlank() && !INTERNAL.matcher(said).find()) {
            return said;
        }
        log.error("Refused with nothing a shop could read, so it was not shown", e);
        return instead;
    }

    /** The sale itself is already committed; the UI offers a reprint. */
    @ExceptionHandler(PrinterException.class)
    public ProblemDetail printerUnavailable(PrinterException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, forTheScreen(e,
                "The bill did not print. Check the printer, then print it again from the Bills screen."));
        problem.setTitle("Printer unavailable");
        return problem;
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ProblemDetail notFound(EntityNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, forTheScreen(e,
                "That is not on this till any more. Refresh the screen and try again."));
        problem.setTitle("Not found");
        return problem;
    }

    /**
     * Anything nobody saw coming. The whole of it - type, message and stack - goes to the log for
     * whoever comes to look; what reaches the till is one sentence saying what to do next.
     *
     * <p>Without this, an unexpected failure fell through to the framework's own error page, which
     * answers a cashier with the word "Internal" and a number. Nothing a shop can act on, and the
     * sort of thing that should never be the last word on a screen somebody is working at.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail somethingWentWrong(Exception e) {
        // Framework refusals already know their own status - a page that is not there, a method
        // that is not allowed - and turning those into "something went wrong" would be a lie as
        // well as a 500. Their status is kept; only the wording is ours.
        if (e instanceof org.springframework.web.ErrorResponse carried) {
            org.springframework.http.HttpStatusCode status = carried.getStatusCode();
            log.debug("Refused with {}", status, e);
            return ProblemDetail.forStatusAndDetail(status, status.is4xxClientError()
                    ? "That could not be done. Reload the screen and try again."
                    : "Something went wrong on this till. Try again, and if it keeps happening"
                            + " call service.");
        }
        log.error("Unhandled failure", e);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Something went wrong on this till. Try again, and if it keeps happening call service.");
        problem.setTitle("Something went wrong");
        return problem;
    }

    @ExceptionHandler(AuthException.class)
    public ProblemDetail unauthenticated(AuthException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, forTheScreen(e,
                "You have been signed out. Sign in again to carry on."));
        problem.setTitle("Sign in required");
        return problem;
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail forbidden(AccessDeniedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, forTheScreen(e,
                "Your account is not allowed to do this. Ask a manager."));
        problem.setTitle("Not allowed for this role");
        return problem;
    }

    /** {@code license: true} tells the screens to refresh the licence banner. */
    @ExceptionHandler(LicenseLockedException.class)
    public ProblemDetail licenseLocked(LicenseLockedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, forTheScreen(e,
                "This till cannot bill at the moment. Open Menu, Licence to see why."));
        problem.setTitle("Billing is locked");
        problem.setProperty("license", true);
        return problem;
    }

    /** Bad input that bean validation cannot express, e.g. a PIN that is too easy or a duplicate username. */
    /**
     * This machine may not bill here: no counter number, the server's own, or no place left. 403
     * rather than 400, because nothing about the request is malformed - the answer would be the
     * same however carefully it were retyped.
     */
    /**
     * The account is in use on another till. Not an error in the usual sense - nothing is wrong
     * and nobody has done anything they should not - so it carries the till's details and leaves
     * the choice to the person: go back, or take the sign-in over.
     */
    @ExceptionHandler(SessionConflictException.class)
    public ProblemDetail signedInElsewhere(SessionConflictException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, forTheScreen(e,
                "This account is already signed in on another till."));
        problem.setTitle("This account is already in use");
        problem.setProperty("sessionConflict", true);
        java.util.Map<String, Object> where = new java.util.LinkedHashMap<>();
        where.put("counterCode", e.getCounterCode());
        where.put("counterName", e.getCounterName());
        where.put("where", e.getWhere());
        where.put("since", e.getSince() == null ? null : e.getSince().toString());
        problem.setProperty("activeSession", where);
        return problem;
    }

    @ExceptionHandler(ClientRefusedException.class)
    public ProblemDetail clientRefused(ClientRefusedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, forTheScreen(e,
                "This counter cannot bill against that server. Set this machine up again."));
        problem.setTitle("This counter cannot bill against that server");
        // Marked so the sign-in screen can tell this apart from a wrong PIN. Nothing is wrong with
        // the person standing there: it is the machine's own setup, or a shop with no place left,
        // and both are settled in the counter's settings window rather than on this screen.
        problem.setProperty("counterSetup", true);
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, forTheScreen(e,
                "That could not be done. Check what is on the screen and try again."));
    }

    /** A refused field that only a service can check, e.g. a barcode already on another item. */
    @ExceptionHandler(FieldRejectedException.class)
    public ProblemDetail fieldRejected(FieldRejectedException e) {
        return invalid(Map.of(e.field(), forTheScreen(e, "was not accepted")), Map.of());
    }

    /**
     * The till is not in a state to do this yet, e.g. billing before the shop's name and address are
     * configured. Distinct from 400: the request was fine, the setup is not.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail notReady(IllegalStateException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, forTheScreen(e,
                "This till is not set up for that yet. Check Settings, then try again."));
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
        // The cause goes to the log, never to the screen: it is database text, and the person
        // reading it is standing at a till with a customer waiting.
        log.error("Could not commit", e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "That could not be saved. Try again, and if it keeps happening call service.");
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
                    ? "has to be one of: " + Arrays.stream(format.getTargetType().getEnumConstants())
                            .map(String::valueOf).collect(Collectors.joining(", "))
                    : "was not filled in the way this screen expects";
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
                ? FieldLabels.of(toCamelCase(unique.group(1))) + " is already in use. Choose another."
                : "Somebody else changed this a moment ago. Refresh the screen and try again.";
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
