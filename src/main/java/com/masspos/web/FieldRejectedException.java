package com.masspos.web;

/**
 * One form field refused by a check bean validation cannot make, e.g. a barcode already on another
 * item. Answered like any other field error, so the screen marks that input.
 */
public class FieldRejectedException extends IllegalArgumentException {

    private final String field;

    /** @param message reads after the field's label: "is already used by Parotta" */
    public FieldRejectedException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
