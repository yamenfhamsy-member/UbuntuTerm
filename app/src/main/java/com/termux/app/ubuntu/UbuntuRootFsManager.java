package com.termux.app.ubuntu;

import android.content.Context;
import android.util.Log;

import com.termux.shared.file.FileUtils;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import java.util.Locale;
import java.util.Properties;

/**
 * Manages the Ubuntu rootfs used by UbuntuTerm.
 *
 * <p>The rootfs is provisioned in one of two ways:
 * <ol>
 *     <li><b>Bundled</b>: the official Ubuntu base image is shipped inside the APK as an xz
 *     compressed archive. On the first start the archive is decompressed into the application
 *     data directory, so that Ubuntu is available immediately and without any network access.</li>
 *     <li><b>Download</b>: fallback used when the bundled archive is not available (for example
 *     when the application data was partially wiped). The image is downloaded from the official
 *     Ubuntu image server and verified against the pinned SHA-256.</li>
 * </ol>
 *
 * <p>In both cases the image is verified before it is unpacked, the extraction is hardened
 * against path traversal and symlink attacks and a failed installation never destroys a
 * previously working rootfs.
 */
public class UbuntuRootFsManager {

    private static final String LOG_TAG = "UbuntuRootFsManager";

    /** Asset holding the manifest of the bundled rootfs. */
    public static final String MANIFEST_ASSET = "ubuntu/rootfs-manifest.properties";

    /** Directory inside the assets holding the bundled rootfs archive. */
    public static final String ARCHIVE_ASSET_DIR = "ubuntu/";

    public enum Phase {
        PROVISIONING,
        DOWNLOADING,
        DECOMPRESSING,
        VERIFYING,
        EXTRACTING,
        FINALIZING,
        DONE
    }

    public enum Source {
        /** The rootfs archive is bundled inside the APK. */
        BUNDLED,
        /** The rootfs archive is downloaded from the official Ubuntu image server. */
        DOWNLOAD
    }

    public interface InstallListener {
        void onProgress(Phase phase, int percent, String message);

        void onError(String message, Throwable throwable);
    }

    /** Result of a state check. */
    public static class State {
        public final boolean installed;
        public final String version;
        public final String detail;

        State(boolean installed, String version, String detail) {
            this.installed = installed;
            this.version = version;
            this.detail = detail;
        }
    }

    private UbuntuRootFsManager() {
    }

    /** Check whether a complete and verified Ubuntu rootfs is installed. */
    public static State getState(Context context) {
        File stamp = new File(UbuntuConstants.STAMP_FILE);
        File rootfs = new File(UbuntuConstants.ROOTFS_DIR);

        if (!stamp.isFile())
            return new State(false, null, "لم يتم تثبيت Ubuntu بعد");

        String stampContent = readText(stamp);
        String version = parseValue(stampContent, "version");
        String checksum = parseValue(stampContent, "sha256");

        if (version == null || !UbuntuConstants.UBUNTU_VERSION.equals(version))
            return new State(false, version, "إصدار Ubuntu المثبت (" + version + ") أقدم من المطلوب (" +
                UbuntuConstants.UBUNTU_VERSION + ")");

        if (!UbuntuConstants.ROOTFS_SHA256.equals(checksum))
            return new State(false, version, "بصمة الصورة المثبتة غير مطابقة");

        if (!rootfs.isDirectory() || !new File(rootfs, "bin/bash").isFile()
            || !new File(rootfs, "etc/os-release").isFile())
            return new State(false, version, "ملفات Ubuntu الأساسية غير مكتملة");

        return new State(true, version, "Ubuntu " + version + " (" + UbuntuConstants.UBUNTU_CODENAME + ") جاهز");
    }

    /** Which provisioning method will be used. */
    public static Source getSource(Context context) {
        try {
            Properties manifest = readManifest(context);
            String archive = manifest.getProperty("archive_name");
            if (archive != null && !archive.isEmpty()) {
                // Make sure the archive is really part of the APK.
                context.getAssets().open(ARCHIVE_ASSET_DIR + archive).close();
                return Source.BUNDLED;
            }
        } catch (Exception e) {
            Log.w(LOG_TAG, "No bundled rootfs available: " + e.getMessage());
        }
        return Source.DOWNLOAD;
    }

    /** Install (or repair) the Ubuntu rootfs. Blocks until finished, must not run on the UI thread. */
    public static void install(Context context, InstallListener listener) {
        try {
            File ubuntuDir = new File(UbuntuConstants.UBUNTU_TERM_DIR);
            File downloadDir = new File(UbuntuConstants.DOWNLOAD_DIR);
            File stagingDir = new File(UbuntuConstants.STAGING_DIR);
            File rootfsDir = new File(UbuntuConstants.ROOTFS_DIR);

            mkdirs(ubuntuDir);
            mkdirs(downloadDir);

            Source source = getSource(context);

            // Always extract into a fresh staging directory so that a partial extraction can never
            // be mistaken for a complete installation.
            deleteRecursive(stagingDir);
            mkdirs(stagingDir);

            if (source == Source.BUNDLED) {
                // The bundled archive is decompressed and extracted in one pass, so that the
                // uncompressed tar archive (110 MB) never has to be written to the device.
                extractBundledArchive(context, downloadDir, stagingDir, listener);
            } else {
                File tarArchive = provisionFromNetwork(context, downloadDir, listener);
                listener.onProgress(Phase.EXTRACTING, 0, "جارٍ فك ضغط نظام الملفات…");
                SafeTarExtractor.extract(tarArchive, stagingDir, extractionProgress(listener));
            }

            listener.onProgress(Phase.FINALIZING, 0, "جارٍ تجهيز بيئة Ubuntu…");
            UbuntuGuestSetup.prepareStagedGuest(context, stagingDir);

            // Swap the freshly extracted rootfs in. The previous installation is only removed after
            // the new one has been moved into place.
            File backup = new File(UbuntuConstants.UBUNTU_TERM_DIR, "rootfs-previous-" + System.currentTimeMillis());
            boolean hadPrevious = rootfsDir.isDirectory();
            if (hadPrevious && !rootfsDir.renameTo(backup))
                throw new IOException("تعذر نقل تثبيت Ubuntu السابق");

            if (!stagingDir.renameTo(rootfsDir)) {
                // Roll back to the previous installation.
                if (hadPrevious) backup.renameTo(rootfsDir);
                throw new IOException("تعذر تفعيل تثبيت Ubuntu الجديد");
            }

            writeStamp(rootfsDir);
            UbuntuGuestSetup.writeGuestMarker(rootfsDir);

            if (hadPrevious) deleteRecursive(backup);

            listener.onProgress(Phase.DONE, 100, "تم تثبيت Ubuntu " + UbuntuConstants.UBUNTU_VERSION + " بنجاح");
        } catch (Throwable t) {
            Log.e(LOG_TAG, "Ubuntu rootfs installation failed", t);
            deleteRecursive(new File(UbuntuConstants.STAGING_DIR));
            String message = t.getMessage() != null ? t.getMessage() : t.toString();
            if (message.contains("ENOSPC") || message.contains("No space left"))
                message = "لا توجد مساحة كافية على الجهاز لتثبيت Ubuntu، احذف بعض الملفات وحاول مرة أخرى";
            listener.onError(message, t);
        }
    }

    private static SafeTarExtractor.ProgressListener extractionProgress(InstallListener listener) {
        return (done, total) -> {
            int percent = total <= 0 ? 0 : (int) Math.min(99, (done * 100) / total);
            listener.onProgress(Phase.EXTRACTING, percent, "جارٍ فك ضغط نظام الملفات…");
        };
    }

    /** Remove the installed rootfs. User data in the home directory is kept. */
    public static void uninstall(Context context) {
        deleteRecursive(new File(UbuntuConstants.ROOTFS_DIR));
        deleteRecursive(new File(UbuntuConstants.STAMP_FILE));
    }

    /**
     * Provision the rootfs from the archive bundled inside the APK.
     *
     * @return the uncompressed tar archive that has to be extracted.
     */
    private static void extractBundledArchive(Context context, File downloadDir, File stagingDir,
                                               InstallListener listener) throws IOException {
        Properties manifest = readManifest(context);

        String sourceSha256 = manifest.getProperty("source_sha256");
        String archiveName = manifest.getProperty("archive_name");
        String archiveSha256 = manifest.getProperty("archive_sha256");
        String archiveFormat = manifest.getProperty("archive_format");
        String tarSha256 = manifest.getProperty("tar_sha256");

        if (sourceSha256 == null || archiveName == null || archiveSha256 == null || tarSha256 == null)
            throw new IOException("ملف بيان Ubuntu المرفق غير مكتمل");

        // The bundled archive must be derived from the official Ubuntu image whose checksum is
        // pinned in the application source code.
        if (!UbuntuConstants.ROOTFS_SHA256.equalsIgnoreCase(sourceSha256))
            throw new IOException("بصمة الصورة الأصلية في الحزمة لا تطابق المصدر الرسمي");

        listener.onProgress(Phase.PROVISIONING, 0, "جارٍ تجهيز Ubuntu من الحزمة…");

        File archive = new File(downloadDir, archiveName);
        copyAsset(context, ARCHIVE_ASSET_DIR + archiveName, archive, listener);

        listener.onProgress(Phase.VERIFYING, 0, "جارٍ التحقق من سلامة الحزمة…");
        String actualArchiveSha256 = sha256(archive);
        if (!archiveSha256.equalsIgnoreCase(actualArchiveSha256))
            throw new IOException("فشل التحقق من بصمة الحزمة: المتوقع " + archiveSha256 +
                " والموجود " + actualArchiveSha256);

        String format = archiveFormat == null ? "tar" : archiveFormat;
        if (!format.equals("xz") && !format.equals("gz") && !format.equals("tar"))
            throw new IOException("صيغة أرشيف غير مدعومة: " + archiveFormat);

        listener.onProgress(Phase.DECOMPRESSING, 0, "جارٍ فك ضغط الحزمة…");
        listener.onProgress(Phase.EXTRACTING, 0, "جارٍ فك ضغط نظام الملفات…");

        // The digest is computed while the archive is extracted, so that the checksum of the
        // uncompressed tar archive is verified without writing it to the device.
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is not available on this device", e);
        }
        try (InputStream fileIn = new BufferedInputStream(new FileInputStream(archive), 64 * 1024);
             InputStream in = format.equals("xz")
                 ? new XZCompressorInputStream(fileIn)
                 : (format.equals("gz") ? new java.util.zip.GZIPInputStream(fileIn, 64 * 1024) : fileIn);
             DigestInputStream digestIn = new DigestInputStream(in, digest)) {
            SafeTarExtractor.extract(digestIn, archive.length(), stagingDir, extractionProgress(listener));
            // The extractor stops at the end of the archive, the rest of the stream is read so
            // that the checksum covers the whole tar archive and not only its first part.
            byte[] rest = new byte[64 * 1024];
            while (digestIn.read(rest) > 0) {
                // The padding of the archive is not used.
            }
        }

        listener.onProgress(Phase.VERIFYING, 0, "جارٍ التحقق من سلامة نظام الملفات…");
        String actualTarSha256 = toHex(digest.digest());
        if (!tarSha256.equalsIgnoreCase(actualTarSha256))
            throw new IOException("فشل التحقق من بصمة نظام الملفات: المتوقع " + tarSha256 +
                " والموجود " + actualTarSha256);

        // The archive is not needed any more, the space is given back to the system.
        deleteRecursive(archive);
    }

    /**
     * Provision the rootfs by downloading the official Ubuntu base image.
     *
     * @return the tar archive that has to be extracted.
     */
    private static File provisionFromNetwork(Context context, File downloadDir, InstallListener listener)
        throws IOException {
        File archive = new File(downloadDir, UbuntuConstants.ROOTFS_FILE_NAME);

        listener.onProgress(Phase.DOWNLOADING, 0, "جارٍ تنزيل Ubuntu " + UbuntuConstants.UBUNTU_VERSION + "…");

        if (!archive.isFile() || !UbuntuConstants.ROOTFS_SHA256.equals(sha256(archive))) {
            download(context, archive, listener);
        }

        listener.onProgress(Phase.VERIFYING, 0, "جارٍ التحقق من سلامة الصورة…");
        String actualChecksum = sha256(archive);
        if (!UbuntuConstants.ROOTFS_SHA256.equals(actualChecksum))
            throw new IOException("فشل التحقق من SHA-256: المتوقع " + UbuntuConstants.ROOTFS_SHA256 +
                " والموجود " + actualChecksum);

        return archive;
    }

    private static Properties readManifest(Context context) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = context.getAssets().open(MANIFEST_ASSET)) {
            properties.load(in);
        }
        return properties;
    }

    private static void copyAsset(Context context, String assetPath, File target, InstallListener listener)
        throws IOException {
        File partial = new File(target.getAbsolutePath() + ".part");
        deleteRecursive(partial);

        try (InputStream in = context.getAssets().open(assetPath);
             OutputStream out = new FileOutputStream(partial)) {
            byte[] buffer = new byte[128 * 1024];
            long copied = 0;
            int read;
            int lastPercent = -1;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                copied += read;
                int percent = (int) Math.min(99, (copied * 100) / Math.max(1, totalAssetSize(context, assetPath)));
                if (percent != lastPercent) {
                    lastPercent = percent;
                    listener.onProgress(Phase.PROVISIONING, percent, "جارٍ تجهيز Ubuntu من الحزمة… " + percent + "%");
                }
            }
            out.flush();
        }

        deleteRecursive(target);
        if (!partial.renameTo(target))
            throw new IOException("تعذر حفظ الحزمة المرفقة");
    }

    private static int totalAssetSize(Context context, String assetPath) {
        try (InputStream in = context.getAssets().open(assetPath)) {
            return Math.max(1, (int) Math.min(Integer.MAX_VALUE, in.available()));
        } catch (IOException e) {
            return 1;
        }
    }

    private static void download(Context context, File target, InstallListener listener) throws IOException {
        File partial = new File(target.getAbsolutePath() + ".part");
        deleteRecursive(partial);

        HttpURLConnection connection = null;
        try {
            URL url = new URL(UbuntuConstants.ROOTFS_URL);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(60000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "UbuntuTerm");

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK)
                throw new IOException("فشل تنزيل Ubuntu: استجابة HTTP " + responseCode);

            long contentLength = connection.getContentLengthLong();
            try (InputStream in = connection.getInputStream();
                 OutputStream out = new FileOutputStream(partial)) {
                byte[] buffer = new byte[128 * 1024];
                long downloaded = 0;
                int read;
                int lastPercent = -1;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                    downloaded += read;
                    if (contentLength > 0) {
                        int percent = (int) Math.min(99, (downloaded * 100) / contentLength);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            listener.onProgress(Phase.DOWNLOADING, percent,
                                "جارٍ تنزيل Ubuntu " + UbuntuConstants.UBUNTU_VERSION + "… " + percent + "%");
                        }
                    }
                }
                out.flush();
            }

            if (partial.length() == 0)
                throw new IOException("الملف المنزَّل فارغ");

            // Only replace a previously downloaded archive once the new one is complete.
            deleteRecursive(target);
            if (!partial.renameTo(target))
                throw new IOException("تعذر حفظ الصورة المنزَّلة");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void writeStamp(File rootfsDir) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("version=").append(UbuntuConstants.UBUNTU_VERSION).append('\n');
        sb.append("codename=").append(UbuntuConstants.UBUNTU_CODENAME).append('\n');
        sb.append("arch=").append(UbuntuConstants.UBUNTU_ARCH).append('\n');
        sb.append("sha256=").append(UbuntuConstants.ROOTFS_SHA256).append('\n');
        sb.append("installed_at=").append(System.currentTimeMillis()).append('\n');
        FileUtils.writeTextToFile("rootfs.stamp", UbuntuConstants.STAMP_FILE,
            java.nio.charset.Charset.defaultCharset(), sb.toString(), false);
    }

    private static String readText(File file) {
        try {
            byte[] data = new byte[(int) Math.min(file.length(), 64 * 1024)];
            try (InputStream in = new FileInputStream(file)) {
                int offset = 0;
                int read;
                while (offset < data.length && (read = in.read(data, offset, data.length - offset)) > 0)
                    offset += read;
            }
            return new String(data, java.nio.charset.Charset.defaultCharset());
        } catch (IOException e) {
            return "";
        }
    }

    private static String parseValue(String content, String key) {
        for (String line : content.split("\n")) {
            int idx = line.indexOf('=');
            if (idx <= 0) continue;
            if (line.substring(0, idx).trim().equals(key))
                return line.substring(idx + 1).trim();
        }
        return null;
    }

    static String sha256(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is not available on this device", e);
        }
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0)
                digest.update(buffer, 0, read);
        }
        return toHex(digest.digest());
    }

    /** The lower case hexadecimal representation of a digest. */
    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes)
            sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
    }

    static void mkdirs(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs())
            throw new IOException("تعذر إنشاء المجلد: " + dir.getAbsolutePath());
    }

    static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory() && !java.nio.file.Files.isSymbolicLink(file.toPath())) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children)
                    deleteRecursive(child);
            }
        }
        if (!file.delete())
            file.deleteOnExit();
    }
}
