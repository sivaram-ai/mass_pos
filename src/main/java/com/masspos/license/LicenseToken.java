package com.masspos.license;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;

/**
 * Reads a licence key: {@code MPL1.<payload>.<signature>}, the payload JSON and both parts base64url
 * without padding, the Ed25519 signature covering {@code MPL1.<payload>}. Written by
 * tools/license/LicenseTool.java; change both together (LicenseToolCompatibilityTest checks they agree).
 */
public final class LicenseToken {

    static final String PREFIX = "MPL1";
    private static final String NOT_A_KEY = "is not a Mass POS licence key";
    private static final String DAMAGED = "is damaged: copy the whole key again";
    private static final String FORGED = "was not issued by your Mass POS supplier, or has been altered";

    /** Unknown fields are ignored, so a till keeps reading licences from a newer tool. */
    private static final ObjectMapper JSON = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private LicenseToken() {
    }

    /** The key as issued: pasted keys arrive wrapped across lines by chat apps and email. */
    public static String normalize(String key) {
        return key == null ? "" : key.replaceAll("\\s+", "");
    }

    /** @throws InvalidLicenseException unless the key is whole and signed by {@code vendorKey} */
    public static LicensePayload read(String key, PublicKey vendorKey) {
        String token = normalize(key);
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || !PREFIX.equals(parts[0]) || parts[1].isEmpty() || parts[2].isEmpty()) {
            throw new InvalidLicenseException(NOT_A_KEY);
        }
        byte[] payload;
        byte[] signature;
        try {
            payload = Base64.getUrlDecoder().decode(parts[1]);
            signature = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw new InvalidLicenseException(DAMAGED);
        }
        if (!verify((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII), signature, vendorKey)) {
            throw new InvalidLicenseException(FORGED);
        }
        LicensePayload licence;
        try {
            licence = JSON.readValue(payload, LicensePayload.class);
        } catch (IOException e) {
            throw new InvalidLicenseException(DAMAGED);
        }
        if (licence == null || !licence.isComplete()) {
            throw new InvalidLicenseException(DAMAGED);
        }
        return licence;
    }

    private static boolean verify(byte[] signed, byte[] signature, PublicKey vendorKey) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(vendorKey);
            verifier.update(signed);
            return verifier.verify(signature);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // A signature of the wrong length is a bad signature, not a crash.
            return false;
        }
    }
}
