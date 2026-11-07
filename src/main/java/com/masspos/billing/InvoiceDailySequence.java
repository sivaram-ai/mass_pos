package com.masspos.billing;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.envers.Audited;

import java.time.LocalDate;

/** The bill number a till shows, which starts at 1 again every day. */
@Entity
@Audited
@Table(name = "invoice_daily_sequence", uniqueConstraints = @UniqueConstraint(
        name = "uk_invoice_daily_sequence_terminal_day", columnNames = {"terminal_code", "business_date"}))
public class InvoiceDailySequence extends DailySequence {

    protected InvoiceDailySequence() {
    }

    public InvoiceDailySequence(String terminalCode, LocalDate businessDate) {
        super(terminalCode, businessDate);
    }
}
