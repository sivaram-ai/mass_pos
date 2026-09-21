package com.masspos.license;

import com.masspos.TestLicensing;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.LocalDate;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LicenseTokenTest {

    private static final PublicKey VENDOR = TestLicensing.vendorKeys().getPublic();
    private static final String MACHINE = "AAAA-BBBB-CCCC-DDDD";

    @Test
    void readsWhatTheVendorSigned() {
        String key = TestLicensing.keyFor(MACHINE, LocalDate.of(2027, 9, 30), 20);

        LicensePayload payload = LicenseToken.read(key, VENDOR);

        assertThat(payload.machineId()).isEqualTo(MACHINE);
        assertThat(payload.customer()).isEqualTo("Sharma General Store");
        assertThat(payload.customerId()).isEqualTo("C001");
        assertThat(payload.plan()).isEqualTo("SUBSCRIPTION");
        assertThat(payload.validUntil()).isEqualTo(LocalDate.of(2027, 9, 30));
        assertThat(payload.graceDays()).isEqualTo(20);
        assertThat(payload.graceEndsOn()).isEqualTo(LocalDate.of(2027, 10, 20));
        assertThat(payload.licenseId()).isNotNull();
    }

    @Test
    void acceptsAKeyPastedWithLineBreaksAndSpaces() {
        String key = TestLicensing.keyFor(MACHINE, LocalDate.of(2027, 9, 30), 15);
        String wrapped = "  " + key.substring(0, 40) + "\r\n" + key.substring(40, 90) + " \n" + key.substring(90) + "\n";

        assertThat(LicenseToken.read(wrapped, VENDOR).machineId()).isEqualTo(MACHINE);
        assertThat(LicenseToken.normalize(wrapped)).isEqualTo(key);
    }

    @Test
    void refusesAKeyWhoseDatesWereEditedAfterSigning() {
        String key = TestLicensing.keyFor(MACHINE, LocalDate.of(2027, 9, 30), 15);
        String[] parts = key.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("2027-09-30", "2037-09-30");
        String forged = parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];

        assertThatThrownBy(() -> LicenseToken.read(forged, VENDOR))
                .isInstanceOf(InvalidLicenseException.class)
                .hasMessage("was not issued by your Mass POS supplier, or has been altered");
    }

    @Test
    void refusesAKeySignedWithAnyOtherKey() {
        String key = TestLicensing.sign(TestLicensing.payload(MACHINE, LocalDate.of(2027, 9, 30), 15, 15),
                TestLicensing.newKeyPair().getPrivate());

        assertThatThrownBy(() -> LicenseToken.read(key, VENDOR))
                .isInstanceOf(InvalidLicenseException.class)
                .hasMessage("was not issued by your Mass POS supplier, or has been altered");
    }

    @Test
    void refusesTextThatIsNotALicenceKey() {
        assertThatThrownBy(() -> LicenseToken.read("hello there", VENDOR))
                .isInstanceOf(InvalidLicenseException.class)
                .hasMessage("is not a Mass POS licence key");
        assertThatThrownBy(() -> LicenseToken.read("", VENDOR))
                .isInstanceOf(InvalidLicenseException.class)
                .hasMessage("is not a Mass POS licence key");
    }

    @Test
    void refusesAKeyCutShortWhenCopied() {
        String key = TestLicensing.keyFor(MACHINE, LocalDate.of(2027, 9, 30), 15);

        assertThatThrownBy(() -> LicenseToken.read(key.substring(0, key.length() - 10), VENDOR))
                .isInstanceOf(InvalidLicenseException.class);
        assertThatThrownBy(() -> LicenseToken.read("MPL1.***.***", VENDOR))
                .isInstanceOf(InvalidLicenseException.class)
                .hasMessage("is damaged: copy the whole key again");
    }

    @Test
    void refusesASignedPayloadWithFieldsMissing() {
        String key = TestLicensing.sign("{\"customer\":\"Sharma General Store\"}", TestLicensing.vendorKeys().getPrivate());

        assertThatThrownBy(() -> LicenseToken.read(key, VENDOR))
                .isInstanceOf(InvalidLicenseException.class)
                .hasMessage("is damaged: copy the whole key again");
    }
}
