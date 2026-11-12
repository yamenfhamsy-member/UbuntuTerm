package com.termux.app.ubuntu;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

public class SafeTarExtractorTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    /** Build a tar archive with a single regular file member. */
    private static byte[] buildTar(String name, String content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        byte[] header = new byte[512];
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(nameBytes, 0, header, 0, Math.min(nameBytes.length, 100));

        // mode, uid, gid, size, mtime
        writeOctal(header, 100, 8, 0644);
        writeOctal(header, 108, 8, 0);
        writeOctal(header, 116, 8, 0);
        writeOctal(header, 124, 12, content.getBytes(StandardCharsets.UTF_8).length);
        writeOctal(header, 136, 12, 0);
        header[156] = '0';
        System.arraycopy("ustar".getBytes(StandardCharsets.UTF_8), 0, header, 257, 5);
        header[263] = '0';
        header[264] = '0';
        // checksum is computed with the checksum field filled with spaces
        for (int i = 148; i < 156; i++) header[i] = ' ';
        int checksum = 0;
        for (byte b : header) checksum += (b & 0xff);
        writeOctal(header, 148, 7, checksum);
        header[155] = ' ';

        out.write(header);

        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        out.write(contentBytes);
        int padding = (512 - (contentBytes.length % 512)) % 512;
        out.write(new byte[padding]);

        // End of archive marker.
        out.write(new byte[1024]);
        return out.toByteArray();
    }

    private static void writeOctal(byte[] header, int offset, int length, long value) {
        String octal = Long.toOctalString(value);
        StringBuilder sb = new StringBuilder(octal);
        while (sb.length() < length - 1) sb.insert(0, '0');
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, header, offset, bytes.length);
        header[offset + length - 1] = 0;
    }

    private File writeArchive(byte[] tar) throws IOException {
        File archive = temporaryFolder.newFile("archive.tar.gz");
        try (OutputStream fileOut = new FileOutputStream(archive);
             GZIPOutputStream gzipOut = new GZIPOutputStream(fileOut)) {
            gzipOut.write(tar);
        }
        return archive;
    }

    @Test
    public void extractsRegularFiles() throws Exception {
        File archive = writeArchive(buildTar("etc/hello.txt", "hello ubuntu"));
        File target = temporaryFolder.newFolder("rootfs");

        long entries = SafeTarExtractor.extract(archive, target, null);

        Assert.assertEquals(1, entries);
        File extracted = new File(target, "etc/hello.txt");
        Assert.assertTrue(extracted.isFile());
        Assert.assertEquals("hello ubuntu",
            new String(java.nio.file.Files.readAllBytes(extracted.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void rejectsPathTraversal() throws Exception {
        File archive = writeArchive(buildTar("../escape.txt", "pwned"));
        File target = temporaryFolder.newFolder("rootfs-traversal");

        try {
            SafeTarExtractor.extract(archive, target, null);
            Assert.fail("Expected the extractor to reject a path traversal member");
        } catch (IOException expected) {
            Assert.assertTrue(expected.getMessage().contains("unsafe archive member"));
        }

        Assert.assertFalse(new File(target.getParentFile(), "escape.txt").exists());
    }

    @Test
    public void rejectsAbsolutePaths() throws Exception {
        File archive = writeArchive(buildTar("/etc/absolute.txt", "nope"));
        File target = temporaryFolder.newFolder("rootfs-absolute");

        try {
            SafeTarExtractor.extract(archive, target, null);
            Assert.fail("Expected the extractor to reject an absolute member path");
        } catch (IOException expected) {
            Assert.assertTrue(expected.getMessage().contains("unsafe archive member"));
        }
    }
}
