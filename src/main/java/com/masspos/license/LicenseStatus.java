package com.masspos.license;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The licence as the screens show it. Licence fields are null when no trustworthy licence is installed.
 *
 * @param message     one or two sentences for the banner and for a refused bill
 * @param machineId   this computer's ID, to send the vendor; null only when it cannot be read
 * @param clockBehind the computer's date is earlier than activity already recorded on this till, so the
 *                    licence is judged at that later date
 */
public record LicenseStatus(LicenseState state, boolean billingAllowed, String message, String machineId,
                            boolean clockBehind, UUID licenseId, String customerId, String customer, String plan,
                            LocalDate issuedOn, LocalDate validUntil, LocalDate graceEndsOn) {
}
