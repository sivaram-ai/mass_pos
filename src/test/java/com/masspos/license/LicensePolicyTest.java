package com.masspos.license;

import com.masspos.license.LicensePolicy.Installed;
import com.masspos.license.LicensePolicy.MachineReading;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LicensePolicyTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 14);
    private static final MachineReading THIS_MACHINE = new MachineReading("AAAA-BBBB-CCCC-DDDD", null);

    @Test
    void activeWellBeforeTheEnd() {
        LicenseStatus status = evaluate(licence(TODAY.plusDays(30), 15, 15), TODAY, null);

        assertThat(status.state()).isEqualTo(LicenseState.ACTIVE);
        assertThat(status.billingAllowed()).isTrue();
        assertThat(status.message()).isEqualTo("Licensed to Sharma General Store until 14-10-2026.");
        assertThat(status.graceEndsOn()).isEqualTo(TODAY.plusDays(45));
        assertThat(status.machineId()).isEqualTo("AAAA-BBBB-CCCC-DDDD");
    }

    @Test
    void warnsInsideTheWarningWindow() {
        LicenseStatus status = evaluate(licence(TODAY.plusDays(14), 15, 15), TODAY, null);

        assertThat(status.state()).isEqualTo(LicenseState.EXPIRING);
        assertThat(status.billingAllowed()).isTrue();
        assertThat(status.message()).isEqualTo("The licence ends on 28-09-2026, in 14 days. Renew it to keep billing.");
    }

    @Test
    void theWarningWindowIsTheVendorsChoice() {
        assertThat(evaluate(licence(TODAY.plusDays(14), 15, 7), TODAY, null).state()).isEqualTo(LicenseState.ACTIVE);
        assertThat(evaluate(licence(TODAY.plusDays(6), 15, 7), TODAY, null).state()).isEqualTo(LicenseState.EXPIRING);
    }

    @Test
    void theLastDayIsStillLicensed() {
        LicenseStatus status = evaluate(licence(TODAY, 15, 15), TODAY, null);

        assertThat(status.state()).isEqualTo(LicenseState.EXPIRING);
        assertThat(status.message()).isEqualTo("The licence ends today, 14-09-2026. Renew it to keep billing.");
    }

    @Test
    void billsThroughTheGracePeriod() {
        LicenseStatus status = evaluate(licence(TODAY.minusDays(1), 15, 15), TODAY, null);

        assertThat(status.state()).isEqualTo(LicenseState.GRACE);
        assertThat(status.billingAllowed()).isTrue();
        assertThat(status.message()).isEqualTo(
                "The licence ended on 13-09-2026. Billing stops after 28-09-2026, in 14 days. Renew it now.");
    }

    @Test
    void theLastGraceDayStillBills() {
        LicenseStatus status = evaluate(licence(TODAY.minusDays(15), 15, 15), TODAY, null);

        assertThat(status.state()).isEqualTo(LicenseState.GRACE);
        assertThat(status.message()).contains("Billing stops after today, 14-09-2026.");
    }

    @Test
    void locksTheDayAfterTheGracePeriod() {
        LicenseStatus status = evaluate(licence(TODAY.minusDays(16), 15, 15), TODAY, null);

        assertThat(status.state()).isEqualTo(LicenseState.EXPIRED);
        assertThat(status.billingAllowed()).isFalse();
        assertThat(status.message()).isEqualTo(
                "The licence ended on 29-08-2026 and its grace period on 13-09-2026. Billing is locked until it is renewed.");
    }

    @Test
    void noGracePeriodLocksTheDayAfterItEnds() {
        assertThat(evaluate(licence(TODAY.minusDays(1), 0, 15), TODAY, null).state()).isEqualTo(LicenseState.EXPIRED);
    }

    @Test
    void aTillWithoutALicenceIsLockedAndShowsItsMachineId() {
        LicenseStatus status = LicensePolicy.evaluate(Installed.NONE, THIS_MACHINE, TODAY, null);

        assertThat(status.state()).isEqualTo(LicenseState.NOT_ACTIVATED);
        assertThat(status.billingAllowed()).isFalse();
        assertThat(status.message()).isEqualTo("This till has no licence yet. Send machine ID AAAA-BBBB-CCCC-DDDD "
                + "to your Mass POS supplier and paste the licence key they send under Menu > Licence.");
        assertThat(status.validUntil()).isNull();
    }

    @Test
    void aLicenceCopiedFromAnotherMachineIsLocked() {
        LicenseStatus status = LicensePolicy.evaluate(licence(TODAY.plusYears(1), 15, 15),
                new MachineReading("ZZZZ-YYYY-XXXX-WWWW", null), TODAY, null);

        assertThat(status.state()).isEqualTo(LicenseState.WRONG_MACHINE);
        assertThat(status.billingAllowed()).isFalse();
        assertThat(status.message()).isEqualTo("The installed licence is for machine AAAA-BBBB-CCCC-DDDD, not this one "
                + "(ZZZZ-YYYY-XXXX-WWWW). Ask your Mass POS supplier for a licence for this machine.");
        assertThat(status.machineId()).isEqualTo("ZZZZ-YYYY-XXXX-WWWW");
    }

    @Test
    void aDamagedLicenceFileIsLocked() {
        LicenseStatus status = LicensePolicy.evaluate(new Installed(null, "is damaged: copy the whole key again"),
                THIS_MACHINE, TODAY, null);

        assertThat(status.state()).isEqualTo(LicenseState.INVALID);
        assertThat(status.billingAllowed()).isFalse();
        assertThat(status.message()).isEqualTo("The installed licence key is damaged: copy the whole key again. "
                + "Paste the key again, or ask your Mass POS supplier for a new one.");
    }

    @Test
    void unreadableHardwareIsLocked() {
        LicenseStatus status = LicensePolicy.evaluate(licence(TODAY.plusYears(1), 15, 15),
                new MachineReading(null, "reg.exe did not answer"), TODAY, null);

        assertThat(status.state()).isEqualTo(LicenseState.INVALID);
        assertThat(status.billingAllowed()).isFalse();
        assertThat(status.message()).isEqualTo(
                "This computer's hardware ID could not be read, so its licence cannot be checked (reg.exe did not answer).");
        assertThat(status.machineId()).isNull();
    }

    @Test
    void windingTheClockBackDoesNotReviveALicence() {
        LocalDate lastActivity = TODAY;
        LocalDate rolledBack = TODAY.minusDays(40);

        LicenseStatus status = evaluate(licence(TODAY.minusDays(30), 15, 15), rolledBack, lastActivity);

        assertThat(status.state()).isEqualTo(LicenseState.EXPIRED);
        assertThat(status.billingAllowed()).isFalse();
        assertThat(status.clockBehind()).isTrue();
        assertThat(status.message()).endsWith("This computer's date, 05-08-2026, is earlier than the last recorded "
                + "activity on 14-09-2026. Correct the date and time.");
    }

    @Test
    void aSlowClockOnAValidLicenceOnlyWarns() {
        LicenseStatus status = evaluate(licence(TODAY.plusDays(100), 15, 15), TODAY.minusDays(2), TODAY);

        assertThat(status.state()).isEqualTo(LicenseState.ACTIVE);
        assertThat(status.billingAllowed()).isTrue();
        assertThat(status.clockBehind()).isTrue();
    }

    private static LicenseStatus evaluate(Installed installed, LocalDate today, LocalDate lastActivityAhead) {
        return LicensePolicy.evaluate(installed, THIS_MACHINE, today, lastActivityAhead);
    }

    private static Installed licence(LocalDate validUntil, int graceDays, int warnDays) {
        return new Installed(new LicensePayload(UUID.randomUUID(), "C001", "Sharma General Store",
                THIS_MACHINE.machineId(), "SUBSCRIPTION", TODAY.minusYears(1), validUntil, graceDays, warnDays), null);
    }
}
