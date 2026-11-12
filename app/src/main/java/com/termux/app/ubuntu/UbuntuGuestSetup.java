package com.termux.app.ubuntu;

import android.content.Context;
import android.content.res.AssetManager;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Prepares the Ubuntu guest: network configuration, shell profile and the Termux:API client.
 */
public class UbuntuGuestSetup {

    private static final String LOG_TAG = "UbuntuGuestSetup";

    private UbuntuGuestSetup() {
    }

    /**
     * Prepare a freshly extracted (staged) rootfs so that it can be used as the Ubuntu guest.
     *
     * @param context   application context.
     * @param stagingDir directory the rootfs was extracted into.
     */
    public static void prepareStagedGuest(Context context, File stagingDir) throws IOException {
        writeNetworkConfig(context, stagingDir);

        File localBin = new File(stagingDir, "usr/local/bin");
        File localLib = new File(stagingDir, "usr/local/lib/ubutumerm");
        UbuntuRootFsManager.mkdirs(localBin);
        UbuntuRootFsManager.mkdirs(localLib);

        // Termux:API client implementation. The dispatcher selects the Perl implementation
        // (Perl is part of the Ubuntu base image) and falls back to Python.
        copyAsset(context, "ubuntu/termux-api", new File(localLib, "termux-api"), 0755);
        copyAsset(context, "ubuntu/termux-api.pl", new File(localLib, "termux-api.pl"), 0755);
        copyAsset(context, "ubuntu/termux-api.py", new File(localLib, "termux-api.py"), 0755);

        // Termux:API command wrappers (upstream scripts, adapted to the guest paths).
        AssetManager assets = context.getAssets();
        String[] wrappers = assets.list("ubuntu/bin");
        if (wrappers != null) {
            for (String wrapper : wrappers) {
                copyAsset(context, "ubuntu/bin/" + wrapper, new File(localBin, wrapper), 0755);
            }
        }

        // Release information inside the guest.
        writeText(new File(stagingDir, "etc/ubunturerm-release"),
            "UbuntuTerm guest\n" +
            "ubuntu_version=" + UbuntuConstants.UBUNTU_VERSION + "\n" +
            "ubuntu_codename=" + UbuntuConstants.UBUNTU_CODENAME + "\n" +
            "ubuntu_arch=" + UbuntuConstants.UBUNTU_ARCH + "\n" +
            "telegram_channel=" + UbuntuConstants.TELEGRAM_CHANNEL + "\n" +
            "developer=" + UbuntuConstants.DEVELOPER_ACCOUNT + "\n");

        // Copyright notice: it is printed by every new login shell of the guest.
        File profileD = new File(stagingDir, "etc/profile.d");
        UbuntuRootFsManager.mkdirs(profileD);
        File credit = new File(profileD, "ubutumerm-credit.sh");
        writeText(credit, UbuntuConstants.buildCreditNotice());
        credit.setExecutable(true, false);


        // Seed the user home with the shell profile of the Ubuntu image. The home directory is
        // stored outside of the rootfs so that it survives rootfs updates and APK updates.
        File home = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
        UbuntuRootFsManager.mkdirs(home);
        copyIfMissing(new File(stagingDir, "root/.bashrc"), new File(home, ".bashrc"));
        copyIfMissing(new File(stagingDir, "root/.profile"), new File(home, ".profile"));
    }

    /**
     * Refresh the guest network configuration before a session is started.
     *
     * The host resolver configuration may change (Wi-Fi, mobile data, VPN), so it is refreshed
     * for every session.
     */
    public static void prepareSession(Context context) {
        try {
            writeNetworkConfig(context, new File(UbuntuConstants.ROOTFS_DIR));
            UbuntuRootFsManager.mkdirs(new File(TermuxConstants.TERMUX_HOME_DIR_PATH));
        } catch (IOException e) {
            android.util.Log.w(LOG_TAG, "Failed to refresh the guest configuration", e);
        }
    }

    /**
     * Write the marker file that shows that the guest has been prepared.
     */
    public static void writeGuestMarker(File rootfsDir) throws IOException {
        writeText(new File(rootfsDir, ".ubutumerm-prepared"),
            "ubuntu_version=" + UbuntuConstants.UBUNTU_VERSION + "\n" +
            "prepared_at=" + System.currentTimeMillis() + "\n");
    }

    /** Write the guest {@code /etc/resolv.conf} and {@code /etc/hosts}. */
    private static void writeNetworkConfig(Context context, File rootfsDir) throws IOException {
        File etc = new File(rootfsDir, "etc");
        UbuntuRootFsManager.mkdirs(etc);

        writeText(new File(etc, "resolv.conf"), readHostResolvConf());
        writeText(new File(etc, "hosts"),
            "127.0.0.1\tlocalhost\n" +
            "127.0.1.1\tubuntu-term\n" +
            "::1\tlocalhost ip6-localhost ip6-loopback\n");
    }

    /**
     * Read the resolver configuration of the host. When it cannot be read (Android restricts
     * access to {@code /etc/resolv.conf} for some API levels) well known public resolvers are
     * used instead.
     */
    private static String readHostResolvConf() {
        File hostResolvConf = new File("/etc/resolv.conf");
        if (hostResolvConf.canRead()) {
            try (InputStream in = new java.io.FileInputStream(hostResolvConf)) {
                byte[] data = new byte[8192];
                int read = in.read(data);
                if (read > 0) {
                    String content = new String(data, 0, read, StandardCharsets.UTF_8);
                    if (content.contains("nameserver"))
                        return content;
                }
            } catch (IOException ignored) {
            }
        }
        return UbuntuConstants.FALLBACK_RESOLV_CONF;
    }

    private static void copyAsset(Context context, String assetPath, File target, int mode) throws IOException {
        try (InputStream in = context.getAssets().open(assetPath);
             OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0)
                out.write(buffer, 0, read);
            out.flush();
        }
        target.setReadable(true, false);
        target.setWritable(true, false);
        target.setExecutable((mode & 0111) != 0, false);
    }

    private static void copyIfMissing(File source, File target) throws IOException {
        if (!source.isFile() || target.exists())
            return;
        UbuntuRootFsManager.mkdirs(target.getParentFile());
        try (InputStream in = new java.io.FileInputStream(source);
             OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0)
                out.write(buffer, 0, read);
            out.flush();
        }
        target.setReadable(true, true);
        target.setWritable(true, true);
    }

    private static void writeText(File file, String content) throws IOException {
        UbuntuRootFsManager.mkdirs(file.getParentFile());
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
        file.setReadable(true, false);
        file.setWritable(true, false);
    }
}
