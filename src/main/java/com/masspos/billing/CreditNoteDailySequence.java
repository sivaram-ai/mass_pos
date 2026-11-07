package com.masspos.billing;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.envers.Audited;

import java.time.LocalDate;

/** The return number a till shows, which starts at 1 again every day, in its own series. */
@Entity
@Audited
@Table(name = "credit_note_daily_sequence", uniqueConstraints = @UniqueConstraint(
        name = "uk_credit_note_daily_sequence_terminal_day", columnNames = {"terminal_code", "business_date"}))
public class CreditNoteDailySequence extends DailySequence {

    protected CreditNoteDailySequence() {
    }

    public CreditNoteDailySequence(String terminalCode, LocalDate businessDate) {
        super(terminalCode, businessDate);
    }
}
