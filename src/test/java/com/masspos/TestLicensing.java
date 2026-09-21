package com.masspos;

import com.masspos.license.LicenseService;
import com.masspos.license.MachineIdentity;
import com.masspos.license.VendorKey;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.LocalDate;
import java.util.Base64;
import java.util.UUID;

/**
 * Licences for tests: a throwaway vendor key and a fixed machine, standing in for the real signing key
 * (which never leaves the vendor) and this computer's hardware. Import {@link Config} into an API test
 * and {@link #install} a licence into its data directory, and billing works as on a licensed till.
 */
public final class TestLicensing {

    public static final String MACHINE_ID = "7E57-7E57-7E57-0001";
    private static final KeyPair VENDOR = newKeyPair();

    private TestLicensing() {
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {

        @Bean
        @Primary
        VendorKey testVendorKey() {
            return new VendorKey(VENDOR.getPublic());
        }

        @Bean
        @Primary
        MachineIdentity testMachine() {
            return () -> MACHINE_ID;
        }
    }

    /** A licence valid for a year on the test machine, placed where an installed till keeps it. */
    public static void install(String dataDir) {
        try {
            Path dir = Files.createDirectories(Path.of(dataDir));
            Files.writeString(dir.resolve(LicenseService.FILE_NAME), keyFor(MACHINE_ID, LocalDate.now().plusYears(1), 15));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String keyFor(String machineId, LocalDate validUntil, int graceDays) {
        return sign(payload(machineId, validUntil, graceDays, 15), VENDOR.getPrivate());
    }

    public static String payload(String machineId, LocalDate validUntil, int graceDays, int warnDays) {
        return """
                {"licenseId":"%s","customerId":"C001","customer":"Sharma General Store","machineId":"%s",\
                "plan":"SUBSCRIPTION","issuedOn":"%s","validUntil":"%s","graceDays":%d,"warnDays":%d}"""
                .formatted(UUID.randomUUID(), machineId, validUntil.minusYears(1), validUntil, graceDays, warnDays);
    }

    /** Signs exactly as tools/license/LicenseTool.java does. */
    public static String sign(String payloadJson, PrivateKey key) {
        String signed = "MPL1." + base64Url(payloadJson.getBytes(StandardCharsets.UTF_8));
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(key);
            signature.update(signed.getBytes(StandardCharsets.US_ASCII));
            return signed + "." + base64Url(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static KeyPair vendorKeys() {
        return VENDOR;
    }

    public static KeyPair newKeyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
