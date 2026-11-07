package com.masspos.billing;

import com.masspos.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;

import java.time.LocalDate;

/**
 * Counter behind the numbers that start again at 1 every morning: one row a till a day, advanced
 * in the same SQLite transaction that inserts the document it numbers. A rolled-back sale gives
 * its number back; a committed one can never be reused. A trigger rejects any move backwards.
 *
 * <p>This sits beside {@link InvoiceSequence}, which still counts a till's documents across the
 * whole financial year and is what the gapless audit trail is kept on. Two counters, because the
 * number a shop reads restarts daily while the one the books are kept on must not.
 *
 * <p>Gapless only holds because SQLite has a single writer and transactions begin IMMEDIATE, so
 * two sales on this terminal can never read the same value.
 */
@MappedSuperclass
public abstract class DailySequence extends BaseEntity {

    @Column(nullable = false, updatable = false, length = 3)
    private String terminalCode;

    /** The business day in {@code IndiaTime.ZONE}, which is when "after midnight" is judged. */
    @Column(nullable = false, updatable = false)
    private LocalDate businessDate;

    @Column(nullable = false)
    private long lastIssued;

    protected DailySequence() {
    }

    protected DailySequence(String terminalCode, LocalDate businessDate) {
        this.terminalCode = terminalCode;
        this.businessDate = businessDate;
    }

    /** Reserves the next number of the day. Only inside the transaction that persists the document. */
    public long next() {
        return ++lastIssued;
    }

    public String getTerminalCode() {
        return terminalCode;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public long getLastIssued() {
        return lastIssued;
    }
}
