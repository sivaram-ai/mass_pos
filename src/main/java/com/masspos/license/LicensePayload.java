package com.masspos.license;

import java.time.LocalDate;
import java.util.UUID;

/**
 * What a licence grants, exactly as the vendor signed it with tools/license/LicenseTool.java. Every
 * field is covered by the signature, so a shop cannot move its dates or its grace period.
 *
 * @param licenseId  this issue of the licence; a renewal gets a new one
 * @param customerId the vendor's reference for the shop, shared by all its tills
 * @param customer   the shop's name, shown on the licence screen
 * @param machineId  the only computer it works on ({@link MachineIdentity})
 * @param plan       SUBSCRIPTION: billing stops once the grace period after {@code validUntil} is over
 * @param validUntil last day it is paid for, inclusive, in India time
 * @param graceDays  days after {@code validUntil} that billing carries on, with a warning
 * @param warnDays   days before {@code validUntil} that the screens start warning
 */
public record LicensePayload(UUID licenseId, String customerId, String customer, String machineId, String plan,
                             LocalDate issuedOn, LocalDate validUntil, int graceDays, int warnDays) {

    /** The last day billing works. */
    public LocalDate graceEndsOn() {
        return validUntil.plusDays(graceDays);
    }

    boolean isComplete() {
        return licenseId != null && notBlank(customerId) && notBlank(customer) && notBlank(machineId) && notBlank(plan)
                && issuedOn != null && validUntil != null && !validUntil.isBefore(issuedOn)
                && graceDays >= 0 && warnDays >= 0;
    }

    private static boolean notBlank(String text) {
        return text != null && !text.isBlank();
    }
}
