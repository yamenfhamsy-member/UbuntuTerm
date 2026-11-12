package com.termux.app.ubuntu;

import android.system.Os;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Minimal, safe tar.gz extractor used to unpack the Ubuntu base image.
 *
 * <p>The extractor is intentionally written from scratch instead of shelling out to {@code tar}:
 * <ul>
 *     <li>every member name is validated (no absolute paths, no {@code ..} traversal),</li>
 *     <li>the real path of the deepest existing ancestor of a member is compared with the real
 *     path of the extraction root before anything is created, and the real path of the member is
 *     compared again after its parent directories were created,</li>
 *     <li>a regular file is never written through an existing symbolic link: the destination is
 *     unlinked first and the file is created with {@code NOFOLLOW_LINKS},</li>
 *     <li>a symbolic link with a relative target is refused when the target escapes the
 *     extraction root, an absolute target is kept because the root of the file system of the
 *     guest is the rootfs, so {@code /etc/alternatives/pager} points into the guest and not to
 *     the file system of the device,</li>
 *     <li>a hard link is only created from a regular file that is inside the extraction root.</li>
 * </ul>
 *
 * <p>The contract of the extractor is therefore: no member of the archive is ever written
 * outside of the extraction root and no member is written through a symbolic link that leaves
 * the extraction root.
 */
class SafeTarExtractor {

    interface ProgressListener {
        void onProgress(long bytesExtracted, long totalBytes);
    }

    private static final int BLOCK_SIZE = 512;

    /** The real path of the extraction root, every member is created below it. */
    private final Path root;

    /** The modes of the directories are applied after the archive was read, like tar does it. */
    private final List<DirectoryMode> directoryModes = new ArrayList<>();

    SafeTarExtractor(File targetDir) throws IOException {
        Path target = targetDir.toPath().toAbsolutePath().normalize();
        Files.createDirectories(target);
        // The real path is used as the root, so that a symbolic link in the path of the target
        // directory cannot be used to redirect the extraction.
        this.root = target.toRealPath();
    }

    /**
     * Extract a gzip compressed tar archive into {@code targetDir}.
     *
     * @param archive   the {@code .tar.gz} file.
     * @param targetDir destination directory.
     * @param listener  optional progress listener.
     * @return number of extracted members.
     */
    static long extract(File archive, File targetDir, ProgressListener listener) throws IOException {
        try (InputStream fileIn = new BufferedInputStream(new FileInputStream(archive), 64 * 1024);
             InputStream in = new GZIPInputStream(fileIn, 64 * 1024)) {
            return extract(in, archive.length(), targetDir, listener);
        }
    }

    /**
     * Extract an uncompressed tar stream into {@code targetDir}.
     *
     * @param in        the tar stream.
     * @param totalSize size of the stream, used for progress reporting.
     * @param targetDir destination directory.
     * @param listener  optional progress listener.
     * @return number of extracted members.
     */
    static long extract(InputStream in, long totalSize, File targetDir, ProgressListener listener) throws IOException {
        SafeTarExtractor extractor = new SafeTarExtractor(targetDir);
        return extractor.doExtract(in, totalSize, listener);
    }

    private long doExtract(InputStream in, long totalBytes, ProgressListener listener) throws IOException {
        long bytesDone = 0;
        long entries = 0;

        {

            byte[] header = new byte[BLOCK_SIZE];
            String longName = null;
            String longLink = null;

            while (true) {
                if (!readFully(in, header, BLOCK_SIZE)) break;
                if (isZeroBlock(header)) {
                    // Two consecutive zero blocks mark the end of the archive.
                    break;
                }

                String name = longName != null ? longName : readString(header, 0, 100);
                String linkName = longLink != null ? longLink : readString(header, 157, 100);
                long size = readOctal(header, 124, 12);
                int typeFlag = header[156] & 0xff;
                int mode = (int) readOctal(header, 100, 8);

                longName = null;
                longLink = null;

                switch (typeFlag) {
                    case 'L': { // GNU long name
                        byte[] data = readData(in, size);
                        longName = trimNull(new String(data, StandardCharsets.UTF_8));
                        skipPadding(in, size);
                        continue;
                    }
                    case 'K': { // GNU long link name
                        byte[] data = readData(in, size);
                        longLink = trimNull(new String(data, StandardCharsets.UTF_8));
                        skipPadding(in, size);
                        continue;
                    }
                    case 'x':
                    case 'g': { // PAX extended headers: only "path" and "linkpath" are relevant.
                        byte[] data = readData(in, size);
                        String pax = new String(data, StandardCharsets.UTF_8);
                        String paxPath = paxValue(pax, "path");
                        String paxLink = paxValue(pax, "linkpath");
                        if (paxPath != null) longName = paxPath;
                        if (paxLink != null) longLink = paxLink;
                        skipPadding(in, size);
                        continue;
                    }
                    default:
                        break;
                }

                Path destination = resolveSafePath(name);
                if (destination == null) {
                    throw new IOException("Refusing to extract unsafe archive member: \"" + name + "\"");
                }
                if (destination.equals(root) && typeFlag != '5') {
                    throw new IOException("Refusing to extract the member \"" + name + "\" over the extraction root");
                }

                switch (typeFlag) {
                    case '5': { // directory
                        ensureParent(destination);
                        createDirectory(destination);
                        directoryModes.add(new DirectoryMode(destination, mode));
                        skipData(in, size);
                        break;
                    }
                    case '0':
                    case '\0':
                    case '7': { // regular file
                        ensureParent(destination);
                        // The destination is unlinked first: a member must never be written
                        // through a symbolic link that was extracted before it.
                        Files.deleteIfExists(destination);
                        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
                            throw new IOException("Refusing to write through \"" + destination + "\"");
                        try (OutputStream out = openOutputStream(destination)) {
                            bytesDone += copy(in, out, size, listener, bytesDone, totalBytes);
                        }
                        applyMode(destination, mode, false);
                        skipPadding(in, size);
                        break;
                    }
                    case '2': { // symbolic link
                        ensureParent(destination);
                        String target = validateLinkTarget(destination, linkName);
                        if (target == null)
                            throw new IOException("Refusing to create unsafe symlink \"" + name + "\" -> \"" + linkName + "\"");
                        Files.deleteIfExists(destination);
                        Path linkTarget;
                        try {
                            linkTarget = Paths.get(target);
                        } catch (InvalidPathException e) {
                            throw new IOException("Invalid symlink target \"" + target + "\" of the member \"" + name + "\"", e);
                        }
                        try {
                            Files.createSymbolicLink(destination, linkTarget);
                        } catch (UnsupportedOperationException | IOException e) {
                            throw new IOException("Failed to create symlink \"" + name + "\": " + e.getMessage(), e);
                        }
                        break;
                    }
                    case '1': { // hard link
                        ensureParent(destination);
                        Path source = resolveSafePath(linkName);
                        if (source == null)
                            throw new IOException("Refusing to create hard link to unsafe path: \"" + linkName + "\"");
                        if (Files.isSymbolicLink(source))
                            throw new IOException("Refusing to create a hard link to the symbolic link \"" + linkName + "\"");
                        Files.deleteIfExists(destination);
                        try {
                            Files.createLink(destination, source);
                        } catch (IOException | UnsupportedOperationException e) {
                            // Fall back to a copy when hard links are not supported.
                            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS))
                                throw new IOException("Failed to create hard link \"" + name + "\": " + e.getMessage(), e);
                            try {
                                Files.copy(source, destination, LinkOption.NOFOLLOW_LINKS);
                            } catch (IOException copyError) {
                                throw new IOException("Failed to create hard link \"" + name + "\": " + copyError.getMessage(), copyError);
                            }
                            verifyInsideRoot(destination.toRealPath(), destination);
                            applyMode(destination, mode, false);
                        }
                        break;
                    }
                    default: {
                        // Character/block devices and FIFOs are not part of the Ubuntu base image.
                        skipData(in, size);
                        skipPadding(in, size);
                        break;
                    }
                }

                entries++;
                if (listener != null) listener.onProgress(bytesDone, totalBytes);
            }
        }

        // The directories are made accessible again at the end, so that a directory of the
        // archive that is not writable does not stop the extraction of its content.
        for (DirectoryMode directoryMode : directoryModes)
            applyMode(directoryMode.path, directoryMode.mode, true);

        return entries;
    }

    /**
     * Resolve an archive member name to a path inside the extraction root.
     *
     * @return the resolved path or {@code null} when the name is unsafe.
     */
    private Path resolveSafePath(String name) {
        if (name == null || name.isEmpty()) return null;
        String normalized = name.replace('\\', '/');
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        if (normalized.startsWith("/")) return null; // absolute paths are not allowed
        Path candidate = root.resolve(normalized).normalize();
        if (!candidate.startsWith(root)) return null; // path traversal
        return candidate;
    }

    /**
     * Validate a symlink target and return the value that should be passed to
     * {@link Files#createSymbolicLink(Path, Path)}.
     */
    private String validateLinkTarget(Path linkPath, String target) {
        if (target == null || target.isEmpty()) return null;
        // An absolute target is allowed: inside the guest the root of the file system is the
        // rootfs, so a link to "/etc/alternatives/pager" points into the guest and not to the
        // file system of the device. A relative target is rejected when it leaves the rootfs.
        if (target.startsWith("/")) return target;
        Path parent = linkPath.getParent();
        Path resolved = parent.resolve(target).normalize();
        if (!resolved.startsWith(root)) return null; // escapes the rootfs
        return target;
    }

    /**
     * Make sure that the parent directory of a member exists inside the extraction root.
     *
     * <p>The deepest existing ancestor is resolved before anything is created: an archive that
     * redirects a directory with a symbolic link must not be able to write outside of the root.
     */
    private void ensureParent(Path destination) throws IOException {
        Path parent = destination.getParent();
        if (parent == null) return;
        if (!parent.startsWith(root)) return; // the member is the root directory itself
        Path ancestor = deepestExistingAncestor(parent);
        verifyInsideRoot(resolveRealPath(ancestor), destination);
        Files.createDirectories(parent);
        verifyInsideRoot(parent.toRealPath(), destination);
    }

    /** Create a directory of the archive, a symbolic link at its place is replaced. */
    private void createDirectory(Path destination) throws IOException {
        if (Files.isSymbolicLink(destination))
            Files.deleteIfExists(destination);
        Files.createDirectories(destination);
        verifyInsideRoot(destination.toRealPath(), destination);
    }

    /**
     * The deepest existing ancestor of a path. A symbolic link counts as existing, even when it
     * is dangling, because it would still redirect the members that are created below it.
     */
    private Path deepestExistingAncestor(Path path) {
        Path current = path;
        while (current != null && !Files.exists(current, LinkOption.NOFOLLOW_LINKS))
            current = current.getParent();
        return current != null ? current : root;
    }

    /**
     * The real path of an existing path. A dangling symbolic link is resolved by the extractor
     * itself, because the file system cannot resolve it.
     */
    private Path resolveRealPath(Path path) throws IOException {
        try {
            return path.toRealPath();
        } catch (NoSuchFileException e) {
            if (!Files.isSymbolicLink(path)) throw e;
            Path target = Files.readSymbolicLink(path);
            // An absolute target of a link is resolved by the file system of the device and a
            // relative target is resolved against the directory of the link.
            Path resolved = target.isAbsolute() ? target.normalize()
                : path.getParent().resolve(target).normalize();
            if (!resolved.startsWith(root))
                throw new IOException("Refusing to follow the symbolic link \"" + path + "\" out of the extraction root");
            return resolved;
        }
    }

    /** Refuse everything that would be written outside of the extraction root. */
    private void verifyInsideRoot(Path realPath, Path destination) throws IOException {
        if (!realPath.startsWith(root))
            throw new IOException("Refusing to write outside of the extraction root: \"" + destination + "\"");
    }

    /**
     * Open the destination of a regular file. The file is created without following a symbolic
     * link, the destination was unlinked before.
     */
    private static OutputStream openOutputStream(Path destination) throws IOException {
        try {
            return Files.newOutputStream(destination, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException e) {
            // Not every platform supports the NOFOLLOW_LINKS option.
            return Files.newOutputStream(destination, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        }
    }

    /**
     * Apply the permission bits of the archive exactly.
     *
     * <p>The POSIX permissions are used when the file system supports them, the setuid, setgid
     * and sticky bits are applied with {@code chmod} because they cannot be expressed with the
     * POSIX permission API.
     */
    private static void applyMode(Path path, int mode, boolean directory) {
        if (!applyPosixPermissions(path, mode & 0777))
            applyFallbackPermissions(path.toFile(), mode, directory);
        applySpecialBits(path, mode & 07777);
    }

    /**
     * Apply the permission bits with the POSIX permission API.
     *
     * @return {@code false} when the file system does not support POSIX permissions.
     */
    private static boolean applyPosixPermissions(Path path, int mode) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissionString(mode)));
            return true;
        } catch (UnsupportedOperationException | IOException e) {
            // The file system does not support POSIX permissions (external and FUSE volumes).
            return false;
        }
    }

    /** Fallback for the file systems that do not support the POSIX permission API. */
    private static void applyFallbackPermissions(File file, int mode, boolean directory) {
        boolean ownerExec = (mode & 0100) != 0;
        boolean groupExec = (mode & 0010) != 0;
        boolean otherExec = (mode & 0001) != 0;
        boolean ownerRead = (mode & 0400) != 0;
        boolean groupRead = (mode & 0040) != 0;
        boolean otherRead = (mode & 0004) != 0;
        boolean ownerWrite = (mode & 0200) != 0;
        boolean groupWrite = (mode & 0020) != 0;
        boolean otherWrite = (mode & 0002) != 0;

        file.setReadable(ownerRead || groupRead || otherRead || directory);
        file.setWritable(ownerWrite || groupWrite || otherWrite);
        // Directories must be traversable, regular files only when the archive marks them executable.
        file.setExecutable(directory || ownerExec || groupExec || otherExec);
    }

    /** Apply the setuid, setgid and sticky bits of the archive. */
    private static void applySpecialBits(Path path, int mode) {
        if ((mode & 07000) == 0) return;
        try {
            Os.chmod(path.toAbsolutePath().toString(), mode);
        } catch (Exception | LinkageError ignored) {
            // The special bits cannot always be set: the file system may reject them and the
            // Android runtime stub of the unit tests throws. The permissions of the archive are
            // then applied without them.
        }
    }

    /** The POSIX permission string of the permission bits of a tar header. */
    private static String permissionString(int mode) {
        char[] permissions = new char[9];
        permissions[0] = (mode & 0400) != 0 ? 'r' : '-';
        permissions[1] = (mode & 0200) != 0 ? 'w' : '-';
        permissions[2] = (mode & 0100) != 0 ? 'x' : '-';
        permissions[3] = (mode & 0040) != 0 ? 'r' : '-';
        permissions[4] = (mode & 0020) != 0 ? 'w' : '-';
        permissions[5] = (mode & 0010) != 0 ? 'x' : '-';
        permissions[6] = (mode & 0004) != 0 ? 'r' : '-';
        permissions[7] = (mode & 0002) != 0 ? 'w' : '-';
        permissions[8] = (mode & 0001) != 0 ? 'x' : '-';
        return new String(permissions);
    }

    private static long copy(InputStream in, OutputStream out, long size, ProgressListener listener,
                             long bytesDone, long totalBytes) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long remaining = size;
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) throw new IOException("Unexpected end of archive");
            out.write(buffer, 0, read);
            remaining -= read;
            bytesDone += read;
            if (listener != null) listener.onProgress(bytesDone, totalBytes);
        }
        return size;
    }

    private static byte[] readData(InputStream in, long size) throws IOException {
        byte[] data = new byte[(int) Math.min(size, Integer.MAX_VALUE)];
        int offset = 0;
        while (offset < data.length) {
            int read = in.read(data, offset, data.length - offset);
            if (read < 0) break;
            offset += read;
        }
        return data;
    }

    private static void skipData(InputStream in, long size) throws IOException {
        long remaining = size;
        byte[] buffer = new byte[64 * 1024];
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) throw new IOException("Unexpected end of archive");
            remaining -= read;
        }
    }

    private static void skipPadding(InputStream in, long size) throws IOException {
        long padding = (BLOCK_SIZE - (size % BLOCK_SIZE)) % BLOCK_SIZE;
        skipData(in, padding);
    }

    private static boolean readFully(InputStream in, byte[] buffer, int length) throws IOException {
        int offset = 0;
        while (offset < length) {
            int read = in.read(buffer, offset, length - offset);
            if (read < 0) return false;
            offset += read;
        }
        return true;
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte b : block) if (b != 0) return false;
        return true;
    }

    private static String readString(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) end++;
        return new String(header, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static String trimNull(String value) {
        int end = value.indexOf('\0');
        return end >= 0 ? value.substring(0, end) : value;
    }

    /**
     * Read a numeric field of a tar header.
     *
     * <p>Besides the octal notation the GNU base-256 notation is supported, it is used for
     * values that do not fit into the octal field.
     */
    private static long readOctal(byte[] header, int offset, int length) {
        int first = header[offset] & 0xff;
        if ((first & 0x80) != 0) {
            // The high bit of the first byte marks the field, the remaining bytes contain the
            // value in big endian order.
            byte[] digits = new byte[length - 1];
            System.arraycopy(header, offset + 1, digits, 0, length - 1);
            BigInteger value = first == 0xff ? new BigInteger(digits) : new BigInteger(1, digits);
            return value.bitLength() > 63 ? Long.MAX_VALUE : value.longValue();
        }
        long value = 0;
        boolean valid = false;
        for (int i = offset; i < offset + length; i++) {
            int c = header[i] & 0xff;
            if (c == 0 || c == ' ') {
                if (valid) break;
                continue;
            }
            if (c < '0' || c > '7') return 0;
            value = (value << 3) | (c - '0');
            valid = true;
        }
        return value;
    }

    private static String paxValue(String pax, String key) {
        // PAX records look like "<len> <key>=<value>\n".
        for (String record : pax.split("\n")) {
            int space = record.indexOf(' ');
            if (space < 0) continue;
            String recordKey = record.substring(space + 1);
            int eq = recordKey.indexOf('=');
            if (eq < 0) continue;
            if (recordKey.substring(0, eq).equals(key)) return recordKey.substring(eq + 1);
        }
        return null;
    }

    /** The mode of a directory, it is applied after the archive was read. */
    private static final class DirectoryMode {
        final Path path;
        final int mode;

        DirectoryMode(Path path, int mode) {
            this.path = path;
            this.mode = mode;
        }
    }
}
