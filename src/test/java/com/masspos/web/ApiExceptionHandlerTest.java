package com.masspos.web;

import com.masspos.hardware.PrinterException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a till is allowed to say to the person standing at it.
 *
 * <p>Whatever goes wrong underneath - a database constraint, a driver, a bug nobody saw coming -
 * the sentence on screen is read by a cashier with a customer in front of them. It has to say what
 * happened in their words and what to do next, and it must never be the inside of the program:
 * no exception types, no stack traces, no SQL, no endpoints, no status numbers. Those go to the
 * log, where the person who can use them will look.
 */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    /** The one that matters most: nobody wrote this message, so nobody checked what is in it. */
    @Test
    void neverPutsAnUnexpectedFailureOnTheScreen() {
        RuntimeException leaky = new IllegalThreadStateException(
                "jdbc:sqlite:/var/pos.db SQLITE_CONSTRAINT_CHECK at com.masspos.billing.SaleService.sell");

        ProblemDetail problem = handler.somethingWentWrong(leaky);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(problem.getDetail())
                .isEqualTo("Something went wrong on this till. Try again, and if it keeps happening"
                        + " call service.");
        assertThat(problem.getDetail()).doesNotContain("sqlite", "SQLITE", "jdbc", "com.masspos",
                "IllegalThreadStateException");
    }

    /**
     * A refusal the framework already made, with a status of its own. Turning a page that is not
     * there into "something went wrong on this till" would be a lie as well as a 500.
     */
    @Test
    void keepsTheStatusOfARefusalThatAlreadyHasOne() {
        ProblemDetail problem = handler.somethingWentWrong(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No static resource favicon.ico"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(problem.getDetail()).isEqualTo("That could not be done. Reload the screen and try again.");
        assertThat(problem.getDetail()).doesNotContain("static resource", "favicon");
    }

    /** A save the database refused for a reason none of the checks above caught. */
    @Test
    void saysASaveFailedWithoutSayingWhatTheDatabaseSaid() {
        ProblemDetail problem = handler.commitFailed(new TransactionSystemException(
                "Could not commit JPA transaction",
                new RuntimeException("SQLITE_BUSY: database is locked")));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(problem.getDetail()).doesNotContain("SQLITE", "JPA", "transaction", "locked");
        assertThat(problem.getDetail()).contains("call service");
    }

    /**
     * A clash on a column somebody can do something about. The field is named in the shop's own
     * words - the column it came from is not something a cashier has ever seen.
     */
    @Test
    void namesTheFieldThatClashedRatherThanTheColumn() {
        ProblemDetail problem = handler.conflict(new DataIntegrityViolationException("could not execute statement",
                new RuntimeException("[SQLITE_CONSTRAINT_UNIQUE] UNIQUE constraint failed: product.barcode")));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getDetail()).contains("already in use");
        assertThat(problem.getDetail()).doesNotContain("SQLITE", "UNIQUE constraint", "product.barcode");
    }

    /** And one it cannot name: still a sentence, still something to do. */
    @Test
    void saysWhatToDoWhenItCannotNameTheClash() {
        ProblemDetail problem = handler.conflict(new DataIntegrityViolationException("could not execute statement",
                new RuntimeException("[SQLITE_CONSTRAINT_FOREIGNKEY] FOREIGN KEY constraint failed")));

        assertThat(problem.getDetail()).isEqualTo(
                "Somebody else changed this a moment ago. Refresh the screen and try again.");
    }

    /**
     * The gate that makes the rest of it hold. Not every refusal is worded by hand: an invariant
     * meant to fail in a test, a library, a driver. Those reach this class as an ordinary
     * IllegalArgumentException, whose message used to become the whole of a 400.
     */
    @Test
    void doesNotShowAMessageThatIsTheInsideOfTheProgram() {
        ProblemDetail problem = handler.badRequest(new IllegalArgumentException(
                "Not a UUIDv7: 0193f0c2-7e1a-7c3d-8f21-6b4a1d9e5c70"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getDetail())
                .isEqualTo("That could not be done. Check what is on the screen and try again.");
    }

    /** And the far commoner case: a sentence written for a shop goes through untouched. */
    @Test
    void showsAMessageThatWasWrittenForTheShop() {
        ProblemDetail problem = handler.notReady(new IllegalStateException(
                "This till has no shop name or address yet. Fill them in under Settings before billing."));

        assertThat(problem.getDetail()).startsWith("This till has no shop name");
    }

    /** A field's own clause, which reads after its label on the form. */
    @Test
    void marksAFieldWithoutPastingAClassNameBesideIt() {
        ProblemDetail problem = handler.fieldRejected(
                new FieldRejectedException("barcode", "com.masspos.catalog.Product@3f1a"));

        @SuppressWarnings("unchecked")
        java.util.Map<String, String> errors =
                (java.util.Map<String, String>) problem.getProperties().get("errors");
        assertThat(errors).containsEntry("barcode", "was not accepted");
    }

    /**
     * A printer failure with a path in it. The person reading this is holding a bill that did not
     * come out; where the program keeps its files is not what they need to know.
     */
    @Test
    void doesNotPutAFilePathOnTheScreenWhenABillWillNotPrint() {
        ProblemDetail problem = handler.printerUnavailable(new PrinterException(
                "java.io.IOException: cannot write C:\\Users\\till\\printer-out"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(problem.getDetail())
                .isEqualTo("The bill did not print. Check the printer, then print it again from the"
                        + " Bills screen.");
    }
}
