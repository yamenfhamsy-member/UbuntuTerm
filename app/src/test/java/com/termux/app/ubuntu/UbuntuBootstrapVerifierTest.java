package com.termux.app.ubuntu;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Tests of the verification of the imported bootstrap archive.
 */
public class UbuntuBootstrapVerifierTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    /** The checksum of a file is calculated over its content. */
    @Test
    public void calculatesTheChecksumOfAFile() throws IOException {
        File file = temporaryFolder.newFile("bootstrap.zip");
        writeFile(file, "UbuntuTerm");

        String checksum = UbuntuBootstrapVerifier.sha256(file);

        Assert.assertEquals("the checksum of the content is used", checksum.length(), 64);
        Assert.assertEquals("the checksum is stable",
            checksum, UbuntuBootstrapVerifier.sha256(file));
    }

    /** An empty file has the checksum of the empty content. */
    @Test
    public void calculatesTheChecksumOfAnEmptyFile() throws IOException {
        File file = temporaryFolder.newFile("empty.zip");

        String checksum = UbuntuBootstrapVerifier.sha256(file);

        Assert.assertEquals("the checksum of the empty content",
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", checksum);
    }

    /** The published bootstrap is trusted, another archive is not. */
    @Test
    public void trustsOnlyThePublishedBootstrap() {
        Assert.assertTrue("the published bootstrap is trusted",
            UbuntuBootstrapVerifier.isTrusted(UbuntuBootstrapVerifier.PUBLISHED_SHA256));
        Assert.assertTrue("the case does not matter",
            UbuntuBootstrapVerifier.isTrusted(UbuntuBootstrapVerifier.PUBLISHED_SHA256.toUpperCase()));
        Assert.assertFalse("another archive is not trusted",
            UbuntuBootstrapVerifier.isTrusted("0000000000000000000000000000000000000000000000000000000000000000"));
        Assert.assertFalse("no checksum is not trusted", UbuntuBootstrapVerifier.isTrusted(null));
        Assert.assertFalse("an empty checksum is not trusted", UbuntuBootstrapVerifier.isTrusted(""));
    }

    /** The checksum is written in lower case hexadecimal notation. */
    @Test
    public void writesTheChecksumInHexadecimalNotation() {
        Assert.assertEquals("00", UbuntuBootstrapVerifier.toHex(new byte[]{0}));
        Assert.assertEquals("0f10ff", UbuntuBootstrapVerifier.toHex(new byte[]{0x0f, 0x10, (byte) 0xff}));
        Assert.assertEquals("the empty checksum is empty", "", UbuntuBootstrapVerifier.toHex(new byte[0]));
    }

    /** Write the content of a file. */
    private static void writeFile(File file, String content) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
