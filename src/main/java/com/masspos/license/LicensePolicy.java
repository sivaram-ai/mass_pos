package com.masspos.license;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/** Decides what a licence allows on a given day. No I/O, so every date rule is tested directly. */
final class LicensePolicy {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    /**
     * The licence file as last read.
     *
     * @param problem why the file cannot be trusted, reading after "The installed licence key"; null if fine
     */
    record Installed(LicensePayload payload, String problem) {

        static final Installed NONE = new Installed(null, null);
    }

    /** @param problem why the ID could not be read; null when {@code machineId} is set */
    record MachineReading(String machineId, String problem) {
    }

    private LicensePolicy() {
    }

    /**
     * @param today        this computer's date in India time
     * @param lastActivity the latest recorded activity on this till when it is later than {@code today},
     *                     else null. The licence is then judged at that date, so turning the clock back
     *                     never extends it.
     */
    static LicenseStatus evaluate(Installed installed, MachineReading machine, LocalDate today, LocalDate lastActivity) {
        boolean clockBehind = lastActivity != null && lastActivity.isAfter(today);
        LocalDate date = clockBehind ? lastActivity : today;
        String clockNote = clockBehind
                ? " This computer's date, %s, is earlier than the last recorded activity on %s. Correct the date and time."
                .formatted(DATE.format(today), DATE.format(lastActivity))
                : "";

        if (machine.machineId() == null) {
            return locked(LicenseState.INVALID, "This computer's hardware ID could not be read, so its licence cannot "
                    + "be checked (" + machine.problem() + ").", machine, clockBehind);
        }
        if (installed.problem() != null) {
            return locked(LicenseState.INVALID, "The installed licence key " + installed.problem()
                    + ". Paste the key again, or ask your Mass POS supplier for a new one." + clockNote, machine, clockBehind);
        }
        LicensePayload licence = installed.payload();
        if (licence == null) {
            return locked(LicenseState.NOT_ACTIVATED, "This till has no licence yet. Send machine ID "
                    + machine.machineId() + " to your Mass POS supplier and paste the licence key they send under "
                    + "Menu > Licence.", machine, clockBehind);
        }
        if (!licence.machineId().equals(machine.machineId())) {
            return locked(LicenseState.WRONG_MACHINE, "The installed licence is for machine %s, not this one (%s). "
                    .formatted(licence.machineId(), machine.machineId())
                    + "Ask your Mass POS supplier for a licence for this machine.", machine, clockBehind);
        }

        LicenseState state;
        String message;
        if (!date.isAfter(licence.validUntil())) {
            long daysLeft = ChronoUnit.DAYS.between(date, licence.validUntil());
            if (daysLeft < licence.warnDays()) {
                state = LicenseState.EXPIRING;
                message = "The licence ends " + when(licence.validUntil(), daysLeft, "on ") + ". Renew it to keep billing.";
            } else {
                state = LicenseState.ACTIVE;
                message = "Licensed to %s until %s.".formatted(licence.customer(), DATE.format(licence.validUntil()));
            }
        } else if (!date.isAfter(licence.graceEndsOn())) {
            state = LicenseState.GRACE;
            message = "The licence ended on %s. Billing stops after %s. Renew it now.".formatted(
                    DATE.format(licence.validUntil()),
                    when(licence.graceEndsOn(), ChronoUnit.DAYS.between(date, licence.graceEndsOn()), ""));
        } else {
            state = LicenseState.EXPIRED;
            message = "The licence ended on %s and its grace period on %s. Billing is locked until it is renewed."
                    .formatted(DATE.format(licence.validUntil()), DATE.format(licence.graceEndsOn()));
        }
        return new LicenseStatus(state, state.allowsBilling(), message + clockNote, machine.machineId(), clockBehind,
                licence.licenseId(), licence.customerId(), licence.customer(), licence.plan(), licence.issuedOn(),
                licence.validUntil(), licence.graceEndsOn());
    }

    /** "today, 14-09-2026" or "on 28-09-2026, in 14 days". */
    private static String when(LocalDate day, long daysAway, String preposition) {
        if (daysAway == 0) {
            return "today, " + DATE.format(day);
        }
        return "%s%s, in %d day%s".formatted(preposition, DATE.format(day), daysAway, daysAway == 1 ? "" : "s");
    }

    private static LicenseStatus locked(LicenseState state, String message, MachineReading machine, boolean clockBehind) {
        return new LicenseStatus(state, false, message, machine.machineId(), clockBehind,
                null, null, null, null, null, null, null);
    }
}
