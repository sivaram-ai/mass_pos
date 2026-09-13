package com.masspos.hardware;

/** The printer could not be reached or stopped accepting data. Never rolls back a sale. */
public class PrinterException extends RuntimeException {

    public PrinterException(String message) {
        super(message);
    }

    public PrinterException(String message, Throwable cause) {
        super(message, cause);
    }
}
