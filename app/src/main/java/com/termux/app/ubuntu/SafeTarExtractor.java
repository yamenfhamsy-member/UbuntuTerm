package com.termux.app.ubuntu;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.GZIPInputStream;

/**
 * Minimal, safe tar.gz extractor used to unpack the Ubuntu base image.
 *
 * <p>The extractor is intentionally written from scratch instead of shelling out to {@code tar}:
 * <ul>
 *     <li>every member name is validated (no absolute paths, no {@code ..} traversal),</li>
 *     <li>symlink targets are validated so that a link can never point outside of the rootfs,</li>
 *     <li>the real path of the parent directory of every written file is checked against the
 *     real path of the extraction root, which prevents symlink redirection attacks.</li>
 * </ul>
 */
class SafeTarExtractor {

    interface ProgressListener {
        void onProgress(long bytesExtracted, long totalBytes);
    }

    private static final int BLOCK_SIZE = 512;

    private final Path root;
    private final Path rootReal;

    SafeTarExtractor(File targetDir) throws IOException {
        this.root = targetDir.toPath().toAbsolutePath().normalize();
        Files.createDirectories(this.root);
        this.rootReal = this.root.toRealPath();
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

                switch (typeFlag) {
                    case '5': { // directory
                        Files.createDirectories(destination);
                        applyMode(destination, mode, true);
                        skipData(in, size);
                        break;
                    }
                    case '0':
                    case '\0':
                    case '7': { // regular file
                        ensureParent(destination);
                        try (OutputStream out = new FileOutputStream(destination.toFile())) {
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
                        try {
                            Files.createSymbolicLink(destination, Paths.get(target));
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
                        Files.deleteIfExists(destination);
                        try {
                            Files.createLink(destination, source);
                        } catch (IOException e) {
                            // Fall back to a copy when hard links are not supported.
                            if (Files.isRegularFile(source)) {
                                Files.copy(source, destination);
                            } else {
                                throw new IOException("Failed to create hard link \"" + name + "\": " + e.getMessage(), e);
                            }
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
        for (Path part : candidate) {
            if (part.toString().equals("..")) return null;
        }
        return candidate;
    }

    /**
     * Validate a symlink target and return the value that should be passed to
     * {@link Files#createSymbolicLink(Path, Path)}.
     */
    private String validateLinkTarget(Path linkPath, String target) {
        if (target == null || target.isEmpty()) return null;
        if (target.startsWith("/")) return null; // absolute symlinks may escape the rootfs
        Path parent = linkPath.getParent();
        Path resolved = parent.resolve(target).normalize();
        if (!resolved.startsWith(root)) return null; // escapes the rootfs
        return target;
    }

    private void ensureParent(Path destination) throws IOException {
        Path parent = destination.getParent();
        if (parent == null) return;
        Files.createDirectories(parent);
        // Verify that the parent directory has not been redirected outside of the root by a
        // previously extracted symlink.
        Path parentReal = parent.toRealPath();
        if (!parentReal.startsWith(rootReal))
            throw new IOException("Refusing to write outside of the extraction root: \"" + destination + "\"");
    }

    private static void applyMode(Path path, int mode, boolean directory) {
        File file = path.toFile();
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
            if (read < 0) break;
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

    private static long readOctal(byte[] header, int offset, int length) {
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
}
