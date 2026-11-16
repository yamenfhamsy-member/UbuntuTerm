package com.termux.app.ubuntu;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Verification of the bootstrap archive that the user imports.
 *
 * <p>The bootstrap contains the host runtime of the application (the PRoot binary and the
 * libraries of the Termux prefix), so an archive that was tampered with would be executed with
 * the rights of the application. The archive is therefore checked against the SHA-256 of the
 * bootstrap that was published for this project. An archive with another checksum is not
 * installed silently, the user has to confirm it.
 */
public class UbuntuBootstrapVerifier {

    /**
     * SHA-256 of the bootstrap archive that was published for UbuntuTerm.
     *
     * <p>The archive is the artifact "bootstrap-aarch64" of the build pipeline, it is distributed
     * together with its checksum file.
     */
    public static final String PUBLISHED_SHA256 =
        "92fba67d49916718f1bd5631dc8a6e96586222f8ab720218c4f3e73c85199d9e";

    /** The checksums of the bootstrap archives that are accepted without a confirmation. */
    private static final List<String> TRUSTED_SHA256 = Arrays.asList(PUBLISHED_SHA256);

    private UbuntuBootstrapVerifier() {
    }

    /**
     * Calculate the SHA-256 of a file.
     *
     * @param file the file
     * @return the checksum in lower case hexadecimal notation
     * @throws IOException when the file cannot be read
     */
    public static String sha256(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is not available", e);
        }
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = new FileInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) > 0)
                digest.update(buffer, 0, read);
        }
        return toHex(digest.digest());
    }

    /** Whether the checksum belongs to a bootstrap that was published for this project. */
    public static boolean isTrusted(String checksum) {
        if (checksum == null)
            return false;
        return TRUSTED_SHA256.contains(checksum.trim().toLowerCase(Locale.US));
    }

    /** The checksum in lower case hexadecimal notation. */
    public static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(Character.forDigit((b >> 4) & 0xf, 16));
            builder.append(Character.forDigit(b & 0xf, 16));
        }
        return builder.toString();
    }
}
