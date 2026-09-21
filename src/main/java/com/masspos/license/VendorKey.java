package com.masspos.license;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * The public half of the vendor's signing key, which every licence must be signed with.
 *
 * <p>Compiled in and never a setting: a key read from pos.properties or the environment would let a
 * shop sign its own licences. The private half stays with the vendor ({@code LicenseTool keygen}).
 */
public record VendorKey(PublicKey publicKey) {

    /** X.509 SubjectPublicKeyInfo, base64: the line {@code LicenseTool keygen} prints. */
    static final String PUBLIC_KEY = "MCowBQYDK2VwAyEAwzQ+vx2zaVoY40edw3zFUtb64EEOCXpaKOTMK0feSvE=";

    public static VendorKey fromBase64(String x509Base64) {
        try {
            return new VendorKey(KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(x509Base64.strip()))));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("The vendor licence key built into this app is not a valid Ed25519 key", e);
        }
    }
}
