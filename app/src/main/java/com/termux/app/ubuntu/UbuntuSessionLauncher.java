package com.termux.app.ubuntu;

import android.content.Context;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Builds the command line and the environment used to start the Ubuntu guest.
 *
 * <p>The guest is a glibc based Ubuntu system executed by PRoot (the Android/Bionic host
 * runtime stays strictly separated from the guest):
 * <ul>
 *     <li>the host prefix ({@code $PREFIX}) is never added to the guest {@code PATH} and never
 *     exposed via {@code LD_LIBRARY_PATH},</li>
 *     <li>the guest environment is created from scratch with {@code env -i} so that no host
 *     variable leaks into the guest,</li>
 *     <li>the guest home directory is the app home directory, so user data survives both rootfs
 *     updates and APK updates.</li>
 * </ul>
 */
public class UbuntuSessionLauncher {

    private static final String LOG_TAG = "UbuntuSessionLauncher";

    /** Fake kernel release so that software detecting the kernel does not break. */
    private static final String FAKE_KERNEL_RELEASE = "\\Linux\\UbuntuTerm\\5.15.0\\aarch64\\localdomain\\-1\\";

    private UbuntuSessionLauncher() {
    }

    /** Whether the host runtime is ready to launch the guest. */
    public static boolean isHostRuntimeReady() {
        return new File(UbuntuConstants.PROOT_BINARY).canExecute();
    }

    /** Path of the host PRoot binary. */
    public static String getProotBinaryPath() {
        return UbuntuConstants.PROOT_BINARY;
    }

    /**
     * Build the full argument list (including the executable) used to start the Ubuntu guest.
     */
    public static String[] buildCommand(Context context) {
        List<String> args = new ArrayList<>();

        args.add(UbuntuConstants.PROOT_BINARY);

        // Fake root inside the guest, required for package installation with apt/dpkg.
        args.add("-0");
        // Termux specific PRoot extensions.
        args.add("--kill-on-exit");
        args.add("--link2symlink");
        args.add("--sysvipc");
        // Correct the size returned by lstat() for symlinks (avoids dpkg warnings).
        args.add("-L");
        args.add("--kernel-release=" + FAKE_KERNEL_RELEASE);

        args.add("--rootfs=" + UbuntuConstants.ROOTFS_DIR);
        args.add("--cwd=" + UbuntuConstants.GUEST_HOME);

        // Kernel file systems of the host.
        args.add("--bind=/dev");
        args.add("--bind=/proc");
        args.add("--bind=/sys");
        args.add("--bind=/dev/urandom:/dev/random");

        // The guest home directory is the application home directory: user data is kept outside
        // of the rootfs and therefore survives rootfs and APK updates.
        args.add("--bind=" + TermuxConstants.TERMUX_HOME_DIR_PATH + ":" + UbuntuConstants.GUEST_HOME);

        // Storage: the application specific external files directory. Android's shared storage
        // is not exposed, because that would require the MANAGE_EXTERNAL_STORAGE permission.
        File externalFiles = context.getExternalFilesDir(null);
        if (externalFiles != null && externalFiles.isDirectory())
            args.add("--bind=" + externalFiles.getAbsolutePath() + ":/sdcard");

        // Clean environment for the guest: no host variable is inherited.
        args.add("/usr/bin/env");
        args.add("-i");
        args.add("HOME=" + UbuntuConstants.GUEST_HOME);
        args.add("PATH=" + UbuntuConstants.GUEST_PATH);
        args.add("TERM=xterm-256color");
        args.add("SHELL=" + UbuntuConstants.GUEST_SHELL);
        args.add("LANG=C.UTF-8");
        args.add("LC_ALL=C.UTF-8");
        args.add("USER=root");
        args.add("LOGNAME=root");
        args.add("TMPDIR=/tmp");
        args.add("PWD=" + UbuntuConstants.GUEST_HOME);
        args.add("TERMUX_APP_PACKAGE=" + TermuxConstants.TERMUX_PACKAGE_NAME);

        // Login shell of the guest.
        args.add(UbuntuConstants.GUEST_SHELL);
        args.add("--login");

        return args.toArray(new String[0]);
    }

    /**
     * Environment overrides applied to the host side process (PRoot). The guest itself gets a
     * clean environment through {@code env -i}.
     */
    public static HashMap<String, String> buildHostEnvironmentOverrides() {
        HashMap<String, String> env = new HashMap<>();
        // Never leak the Bionic library path into the guest.
        env.put("LD_LIBRARY_PATH", "");
        env.put("TMPDIR", "/tmp");
        return env;
    }
}
