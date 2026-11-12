package com.termux.app.ubuntu;

import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.zip.GZIPOutputStream;

public class SafeTarExtractorTest {

    /** The bundled rootfs of the official Ubuntu base image. */
    private static final String BUNDLED_ROOTFS = "ubuntu-base-26.04.1-base-arm64.tar.xz";

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    /** Build a tar archive with a single regular file member. */
    private static byte[] buildTar(String name, String content) throws IOException {
        return buildTar(Member.file(name, 0644, content));
    }

    /** Build a tar archive with the given members. */
    private static byte[] buildTar(Member... members) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        for (Member member : members) {
            byte[] header = new byte[512];
            byte[] nameBytes = member.name.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(nameBytes, 0, header, 0, Math.min(nameBytes.length, 100));
            if (member.linkName != null) {
                byte[] linkBytes = member.linkName.getBytes(StandardCharsets.UTF_8);
                System.arraycopy(linkBytes, 0, header, 157, Math.min(linkBytes.length, 100));
            }

            // mode, uid, gid, size, mtime
            writeOctal(header, 100, 8, member.mode);
            writeOctal(header, 108, 8, 0);
            writeOctal(header, 116, 8, 0);
            writeOctal(header, 124, 12, member.content.length);
            writeOctal(header, 136, 12, 0);
            header[156] = (byte) member.typeFlag;
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
            out.write(member.content);
            int padding = (512 - (member.content.length % 512)) % 512;
            out.write(new byte[padding]);
        }

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

    /** Write a numeric field of a tar header with the GNU base-256 encoding. */
    private static void writeBase256(byte[] header, int offset, int length, long value) {
        header[offset] = (byte) 0x80;
        for (int i = length - 1; i >= 1; i--) {
            header[offset + i] = (byte) (value & 0xff);
            value >>= 8;
        }
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

    /**
     * The official Ubuntu base image contains symbolic links with an absolute target, they point
     * into the guest and must not be refused.
     */
    @Test
    public void createsSymlinksWithAnAbsoluteTarget() throws Exception {
        File archive = writeArchive(buildTar(Member.symlink("usr/bin/pager", "/etc/alternatives/pager")));
        File target = temporaryFolder.newFolder("rootfs-absolute-link");

        long entries = SafeTarExtractor.extract(archive, target, null);

        Assert.assertEquals(1, entries);
        Path link = target.toPath().resolve("usr/bin/pager");
        Assert.assertTrue(Files.isSymbolicLink(link));
        Assert.assertEquals(Paths.get("/etc/alternatives/pager"), Files.readSymbolicLink(link));
    }

    /** A relative target that leaves the extraction root is still refused. */
    @Test
    public void rejectsSymlinksThatEscapeTheRoot() throws Exception {
        File archive = writeArchive(buildTar(Member.symlink("evil", "../../escape")));
        File target = temporaryFolder.newFolder("rootfs-escaping-link");

        try {
            SafeTarExtractor.extract(archive, target, null);
            Assert.fail("Expected the extractor to reject a symlink that escapes the root");
        } catch (IOException expected) {
            Assert.assertTrue(expected.getMessage().contains("unsafe symlink"));
        }

        Assert.assertFalse(Files.exists(target.toPath().resolve("evil"), LinkOption.NOFOLLOW_LINKS));
    }

    /** A member must never be written through a symbolic link that was extracted before it. */
    @Test
    public void doesNotWriteThroughASymlink() throws Exception {
        File outside = temporaryFolder.newFile("outside.txt");
        Files.write(outside.toPath(), "original".getBytes(StandardCharsets.UTF_8));
        File archive = writeArchive(buildTar(
            Member.symlink("evil", outside.getAbsolutePath()),
            Member.file("evil", 0644, "pwned")));
        File target = temporaryFolder.newFolder("rootfs-write-through-link");

        SafeTarExtractor.extract(archive, target, null);

        Assert.assertEquals("original",
            new String(Files.readAllBytes(outside.toPath()), StandardCharsets.UTF_8));
        Path extracted = target.toPath().resolve("evil");
        Assert.assertTrue(Files.isRegularFile(extracted, LinkOption.NOFOLLOW_LINKS));
        Assert.assertEquals("pwned", new String(Files.readAllBytes(extracted), StandardCharsets.UTF_8));
    }

    /** A directory must never be created below a symbolic link that leaves the root. */
    @Test
    public void doesNotCreateDirectoriesOutsideTheRoot() throws Exception {
        File outside = temporaryFolder.newFolder("outside-directory");
        File archive = writeArchive(buildTar(
            Member.symlink("evil", outside.getAbsolutePath()),
            Member.directory("evil/sub", 0755)));
        File target = temporaryFolder.newFolder("rootfs-directory-through-link");

        try {
            SafeTarExtractor.extract(archive, target, null);
            Assert.fail("Expected the extractor to refuse a directory below a symlink that leaves the root");
        } catch (IOException expected) {
            Assert.assertTrue(expected.getMessage().contains("outside of the extraction root"));
        }

        Assert.assertFalse(new File(outside, "sub").exists());
    }

    /** The permissions of the archive are applied exactly, independent of the umask. */
    @Test
    public void appliesThePermissionsOfTheArchive() throws Exception {
        File archive = writeArchive(buildTar(
            Member.file("etc/hello.txt", 0644, "hello"),
            Member.file("bin/run.sh", 0755, "#!/bin/sh"),
            Member.file("etc/shadow", 0600, "root:!:"),
            Member.directory("etc", 0755)));
        File target = temporaryFolder.newFolder("rootfs-permissions");

        SafeTarExtractor.extract(archive, target, null);

        Assert.assertEquals("rw-r--r--", permissions(target, "etc/hello.txt"));
        Assert.assertEquals("rwxr-xr-x", permissions(target, "bin/run.sh"));
        Assert.assertEquals("rw-------", permissions(target, "etc/shadow"));
        Assert.assertEquals("rwxr-xr-x", permissions(target, "etc"));
    }

    private static String permissions(File root, String name) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(root.toPath().resolve(name)));
    }

    /** The size and the mode of a member can be encoded with the GNU base-256 notation. */
    @Test
    public void readsGnuBase256Fields() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] header = new byte[512];
        byte[] nameBytes = "bin/run.sh".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(nameBytes, 0, header, 0, nameBytes.length);
        writeBase256(header, 100, 8, 0755);
        writeBase256(header, 124, 12, 9);
        header[156] = '0';
        System.arraycopy("ustar".getBytes(StandardCharsets.UTF_8), 0, header, 257, 5);
        header[263] = '0';
        header[264] = '0';
        for (int i = 148; i < 156; i++) header[i] = ' ';
        int checksum = 0;
        for (byte b : header) checksum += (b & 0xff);
        writeOctal(header, 148, 7, checksum);
        header[155] = ' ';
        out.write(header);
        out.write("#!/bin/sh".getBytes(StandardCharsets.UTF_8));
        out.write(new byte[512 - 9]);
        out.write(new byte[1024]);

        File archive = writeArchive(out.toByteArray());
        File target = temporaryFolder.newFolder("rootfs-base256");

        long entries = SafeTarExtractor.extract(archive, target, null);

        Assert.assertEquals(1, entries);
        Path extracted = target.toPath().resolve("bin/run.sh");
        Assert.assertEquals("#!/bin/sh", new String(Files.readAllBytes(extracted), StandardCharsets.UTF_8));
        Assert.assertEquals("rwxr-xr-x", permissions(target, "bin/run.sh"));
    }

    /**
     * Extract the bundled Ubuntu base image. The archive is generated by the build workflow and
     * is not part of the repository, the test is skipped when it is missing.
     */
    @Test
    public void extractsTheBundledRootfs() throws Exception {
        File archive = findBundledRootfs();
        Assume.assumeTrue("The bundled rootfs is not available", archive.exists());
        File target = temporaryFolder.newFolder("rootfs-bundled");

        try (InputStream in = openBundledRootfs(archive)) {
            long entries = SafeTarExtractor.extract(in, archive.length(), target, null);
            Assert.assertTrue("Unexpected number of members: " + entries, entries > 6000);
        }

        Path pager = target.toPath().resolve("usr/bin/pager");
        Assert.assertTrue(Files.isSymbolicLink(pager));
        Assert.assertEquals(Paths.get("/etc/alternatives/pager"), Files.readSymbolicLink(pager));

        Path varRun = target.toPath().resolve("var/run");
        Assert.assertTrue(Files.isSymbolicLink(varRun));
        Assert.assertEquals(Paths.get("/run"), Files.readSymbolicLink(varRun));
    }

    /** Open the compressed bundled rootfs. */
    private static InputStream openBundledRootfs(File archive) throws IOException {
        try {
            return new XZCompressorInputStream(new BufferedInputStream(new FileInputStream(archive), 64 * 1024));
        } catch (NoClassDefFoundError e) {
            // The XZ support of commons-compress needs the optional "org.tukaani:xz" library.
            Assume.assumeNoException("The XZ support is not available", e);
            return null;
        }
    }

    /** The bundled rootfs of the application, {@code null} when it was not generated. */
    private static File findBundledRootfs() {
        String[] candidates = {
            "src/main/assets/ubuntu/" + BUNDLED_ROOTFS,
            "app/src/main/assets/ubuntu/" + BUNDLED_ROOTFS,
            "../src/main/assets/ubuntu/" + BUNDLED_ROOTFS
        };
        for (String candidate : candidates) {
            File file = new File(candidate);
            if (file.isFile()) return file;
        }
        return new File(candidates[0]);
    }

    /** A member of a test archive. */
    private static final class Member {

        final String name;
        final int typeFlag;
        final int mode;
        final byte[] content;
        final String linkName;

        Member(String name, int typeFlag, int mode, byte[] content, String linkName) {
            this.name = name;
            this.typeFlag = typeFlag;
            this.mode = mode;
            this.content = content;
            this.linkName = linkName;
        }

        static Member file(String name, int mode, String content) {
            return new Member(name, '0', mode, content.getBytes(StandardCharsets.UTF_8), null);
        }

        static Member directory(String name, int mode) {
            return new Member(name, '5', mode, new byte[0], null);
        }

        static Member symlink(String name, String target) {
            return new Member(name, '2', 0777, new byte[0], target);
        }
    }
}
