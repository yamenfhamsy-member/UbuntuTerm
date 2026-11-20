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
 * Prepares a Linux distribution guest: network configuration, package manager sources and the
 * Termux:API client.
 */
public class GuestSetup {

    private static final String LOG_TAG = "GuestSetup";

    private GuestSetup() {
    }

    /**
     * Prepare a freshly extracted (staged) rootfs so that it can be used as the guest.
     *
     * @param context   application context.
     * @param distro    the distribution being prepared.
     * @param stagingDir directory the rootfs was extracted into.
     */
    public static void prepareStagedGuest(Context context, GuestDistro distro, File stagingDir) throws IOException {
        writeNetworkConfig(context, stagingDir);
        writePackageConfig(stagingDir, distro);

        File localBin = new File(stagingDir, "usr/local/bin");
        File localLib = new File(stagingDir, "usr/local/lib/ubutumerm");
        UbuntuRootFsManager.mkdirs(localBin);
        UbuntuRootFsManager.mkdirs(localLib);

        // The mount points of the shared storage of the device.
        UbuntuRootFsManager.mkdirs(new File(stagingDir, "storage/emulated/0"));
        UbuntuRootFsManager.mkdirs(new File(stagingDir, "sdcard"));

        // Termux:API client implementation.
        copyAsset(context, "ubuntu/termux-api", new File(localLib, "termux-api"), 0755);
        copyAsset(context, "ubuntu/termux-api.pl", new File(localLib, "termux-api.pl"), 0755);
        copyAsset(context, "ubuntu/termux-api.py", new File(localLib, "termux-api.py"), 0755);

        // Termux:API command wrappers.
        AssetManager assets = context.getAssets();
        String[] wrappers = assets.list("ubuntu/bin");
        if (wrappers != null) {
            for (String wrapper : wrappers) {
                copyAsset(context, "ubuntu/bin/" + wrapper, new File(localBin, wrapper), 0755);
            }
        }

        // Release information inside the guest.
        writeText(new File(stagingDir, "etc/ubuntuterm-release"),
            "UbuntuTerm guest\n" +
            "distro=" + distro.id() + "\n" +
            "distro_name=" + distro.name() + "\n" +
            "distro_version=" + distro.version() + "\n" +
            "distro_codename=" + distro.codename() + "\n" +
            "distro_arch=" + distro.arch() + "\n" +
            "package_manager=" + distro.packageManager() + "\n" +
            "telegram_channel=https://t.me/thorfin963\n" +
            "developer=https://t.me/Ya_ali963\n");

        // Copyright notice: it is printed by every new login shell of the guest.
        File profileD = new File(stagingDir, "etc/profile.d");
        UbuntuRootFsManager.mkdirs(profileD);
        File credit = new File(profileD, "ubutumerm-credit.sh");
        writeText(credit, distro.buildCreditNotice());
        credit.setExecutable(true, false);

        // Seed the user home with the shell profile of the guest image.
        File home = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
        UbuntuRootFsManager.mkdirs(home);
        copyIfMissing(new File(stagingDir, "root/.bashrc"), new File(home, ".bashrc"));
        copyIfMissing(new File(stagingDir, "root/.profile"), new File(home, ".profile"));
    }

    /**
     * Refresh the guest network configuration before a session is started.
     */
    public static void prepareSession(Context context, GuestDistro distro) {
        try {
            File rootfsDir = new File(distro.rootfsDir());
            writeNetworkConfig(context, rootfsDir);
            writePackageConfig(rootfsDir, distro);
            UbuntuRootFsManager.mkdirs(new File(TermuxConstants.TERMUX_HOME_DIR_PATH));
        } catch (IOException e) {
            android.util.Log.w(LOG_TAG, "Failed to refresh the guest configuration", e);
        }
        if (UbuntuStorage.hasStoragePermission(context))
            UbuntuStorage.setupStorageSymlinks(context);
    }

    /**
     * Write the marker file that shows that the guest has been prepared.
     */
    public static void writeGuestMarker(File rootfsDir, GuestDistro distro) throws IOException {
        writeText(new File(rootfsDir, ".ubutumerm-prepared"),
            "distro=" + distro.id() + "\n" +
            "distro_version=" + distro.version() + "\n" +
            "prepared_at=" + System.currentTimeMillis() + "\n");
    }

    /** Write the guest {@code /etc/resolv.conf} and {@code /etc/hosts}. */
    private static void writeNetworkConfig(Context context, File rootfsDir) throws IOException {
        File etc = new File(rootfsDir, "etc");
        UbuntuRootFsManager.mkdirs(etc);

        writeText(new File(etc, "resolv.conf"), readHostResolvConf());
        writeText(new File(etc, "hosts"),
            "127.0.0.1\tlocalhost\n" +
            "127.0.1.1\tguest-term\n" +
            "::1\tlocalhost ip6-localhost ip6-loopback\n");
    }

    /**
     * Write the package sources of the guest.
     */
    private static void writePackageConfig(File rootfsDir, GuestDistro distro) throws IOException {
        String pm = distro.packageManager();

        if ("apt".equals(pm)) {
            writeAptConfig(rootfsDir, distro);
        } else if ("pacman".equals(pm)) {
            writePacmanConfig(rootfsDir, distro);
        }
    }

    private static void writeAptConfig(File rootfsDir, GuestDistro distro) throws IOException {
        File sourcesListD = new File(rootfsDir, "etc/apt/sources.list.d");
        UbuntuRootFsManager.mkdirs(sourcesListD);

        writeText(new File(sourcesListD, "ubutumerm.sources"), distro.packageSources());

        File aptConfD = new File(rootfsDir, "etc/apt/apt.conf.d");
        UbuntuRootFsManager.mkdirs(aptConfD);

        writeText(new File(aptConfD, "99ubutumerm-proot"), distro.aptConfig());
    }

    private static void writePacmanConfig(File rootfsDir, GuestDistro distro) throws IOException {
        File etcPacmanD = new File(rootfsDir, "etc/pacman.d");
        UbuntuRootFsManager.mkdirs(etcPacmanD);

        writeText(new File(etcPacmanD, "ubutumerm.conf"), distro.pacmanConfig());
    }

    /**
     * Read the resolver configuration of the host.
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
        return distroFallbackResolvConf();
    }

    private static String distroFallbackResolvConf() {
        return "nameserver 1.1.1.1\nnameserver 8.8.8.8\n";
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
