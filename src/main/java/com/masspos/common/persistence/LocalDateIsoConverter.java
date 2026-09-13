package com.masspos.common.persistence;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.LocalDate;

/**
 * Stores every {@link LocalDate} as ISO-8601 text ({@code 2026-09-11}). sqlite-jdbc would otherwise
 * write epoch millis of local midnight, which silently shifts a GST invoice date if the machine's
 * time zone is ever misconfigured. Text is also readable by an auditor and sorts correctly.
 */
@Converter(autoApply = true)
public class LocalDateIsoConverter implements AttributeConverter<LocalDate, String> {

    @Override
    public String convertToDatabaseColumn(LocalDate date) {
        return date == null ? null : date.toString();
    }

    @Override
    public LocalDate convertToEntityAttribute(String text) {
        return text == null ? null : LocalDate.parse(text);
    }
}
