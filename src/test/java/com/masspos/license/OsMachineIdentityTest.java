package com.masspos.license;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OsMachineIdentityTest {

    @Test
    void readsAValueFromRegQueryOutput() {
        String output = """

                HKEY_LOCAL_MACHINE\\SYSTEM\\HardwareConfig
                    LastConfig    REG_SZ    {4c4c4544-0052-4610-805a-c2c04f365432}

                """;

        assertThat(OsMachineIdentity.registryValue(output, "LastConfig"))
                .isEqualTo("{4c4c4544-0052-4610-805a-c2c04f365432}");
        assertThat(OsMachineIdentity.registryValue(output, "MachineGuid")).isNull();
    }

    @Test
    void readsThePlatformUuidFromIoregOutput() {
        String output = """
                +-o MacBookPro18,3  <class IOPlatformExpertDevice, id 0x100000223, registered, matched, active>
                    {
                      "IOPlatformSerialNumber" = "C02XXXXXXX"
                      "IOPlatformUUID" = "A1B2C3D4-1111-2222-3333-444455556666"
                    }
                """;

        assertThat(OsMachineIdentity.ioregPlatformUuid(output)).isEqualTo("A1B2C3D4-1111-2222-3333-444455556666");
    }

    @Test
    void anIdIsFourGroupsOfReadableCharacters() {
        String id = OsMachineIdentity.fingerprint(List.of("4C4C4544-0052-4610-805A-C2C04F365432",
                "075a1468-bdf7-45c5-b2e1-2bad89faf0ca"));

        // Crockford base32: no I, L, O or U, so an ID read out over the phone cannot be misheard.
        assertThat(id).matches("[0-9A-HJKMNP-TV-Z]{4}(-[0-9A-HJKMNP-TV-Z]{4}){3}");
    }

    @Test
    void theSameHardwareAlwaysGivesTheSameId() {
        String fromCim = OsMachineIdentity.fingerprint(List.of("4C4C4544-0052-4610-805A-C2C04F365432", "075A1468"));
        String fromRegistry = OsMachineIdentity.fingerprint(List.of(" {4c4c4544-0052-4610-805a-c2c04f365432} ", "075a1468"));

        assertThat(fromRegistry).isEqualTo(fromCim);
    }

    @Test
    void aClonedDiskOnOtherHardwareGivesADifferentId() {
        String original = OsMachineIdentity.fingerprint(List.of("4C4C4544-0052-4610-805A-C2C04F365432", "075A1468"));
        String clone = OsMachineIdentity.fingerprint(List.of("5D5D5655-0052-4610-805A-C2C04F365432", "075A1468"));

        assertThat(clone).isNotEqualTo(original);
    }

    @Test
    void refusesToFingerprintAMissingPart() {
        assertThatThrownBy(() -> OsMachineIdentity.fingerprint(List.of("4C4C4544-0052-4610-805A-C2C04F365432", " ")))
                .isInstanceOf(IllegalStateException.class);
    }
}
