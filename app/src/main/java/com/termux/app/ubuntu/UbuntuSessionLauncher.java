package com.termux.app.ubuntu;

import android.content.Context;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
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
        return buildCommandForExecutable(context, null, null, UbuntuConstants.GUEST_HOME);
    }

    /**
     * Build the argument list of PRoot that runs a command inside the guest.
     *
     * <p>The login shell of the guest is started when {@code executable} is {@code null} or
     * empty. A working directory of the host (below {@code /data/data}) is not visible inside
     * the guest, the home directory of the guest is used instead.
     *
     * @param context    application context, used for the bind of the shared storage.
     * @param executable the executable inside the guest or {@code null} for the login shell.
     * @param arguments  the arguments of the executable or {@code null}.
     * @param cwd        the working directory inside the guest or {@code null}.
     */
    public static String[] buildCommandForExecutable(Context context, String executable,
                                                     String[] arguments, String cwd) {
        String guestCwd = (cwd != null && !cwd.isEmpty() && !cwd.startsWith("/data/data/"))
            ? cwd : UbuntuConstants.GUEST_HOME;

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
        args.add("--cwd=" + guestCwd);

        // Kernel file systems of the host.
        args.add("--bind=/dev");
        args.add("--bind=/proc");
        args.add("--bind=/sys");
        args.add("--bind=/dev/urandom:/dev/random");

        // The temporary directory of the host: X11 sockets and PulseAudio need a shared
        // temporary directory, and the packages of the guest write their temporary files here
        // instead of the rootfs.
        File hostTmpDir = new File(TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
        if (!hostTmpDir.isDirectory()) hostTmpDir.mkdirs();
        args.add("--bind=" + hostTmpDir.getAbsolutePath() + ":/tmp");

        // The guest home directory is the application home directory: user data is kept outside
        // of the rootfs and therefore survives rootfs and APK updates.
        args.add("--bind=" + TermuxConstants.TERMUX_HOME_DIR_PATH + ":" + UbuntuConstants.GUEST_HOME);

        // Storage: the shared storage of the device when the user granted the access to it,
        // otherwise the application specific external files directory.
        args.addAll(UbuntuStorage.buildGuestBinds(context));

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
        args.add("PWD=" + guestCwd);
        args.add("TERMUX_APP_PACKAGE=" + TermuxConstants.TERMUX_PACKAGE_NAME);

        if (executable == null || executable.isEmpty()) {
            // Login shell of the guest.
            args.add(UbuntuConstants.GUEST_SHELL);
            args.add("--login");
        } else {
            args.add(executable);
            if (arguments != null)
                Collections.addAll(args, arguments);
        }

        return args.toArray(new String[0]);
    }

    /**
     * Environment overrides applied to the host side process (PRoot). The guest itself gets a
     * clean environment through {@code env -i}.
     */
    public static HashMap<String, String> buildHostEnvironmentOverrides() {
        File hostTmpDir = new File(TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
        if (!hostTmpDir.isDirectory()) hostTmpDir.mkdirs();

        HashMap<String, String> env = new HashMap<>();
        // Never leak the Bionic library path into the guest. The libraries of the host process
        // are resolved through DT_RUNPATH, the PRoot loader must not inherit anything.
        env.put("LD_PRELOAD", "");
        env.put("LD_LIBRARY_PATH", "");
        // PRoot creates its own temporary files, /tmp does not exist on Android.
        env.put("PROOT_TMP_DIR", hostTmpDir.getAbsolutePath());
        env.put("TMPDIR", hostTmpDir.getAbsolutePath());
        return env;
    }

    /**
     * Whether a command has to be executed inside the guest.
     *
     * <p>A path of the host runtime (the application data directory, the system partitions) is
     * executed directly, everything else is treated as a path of the guest file system.
     */
    public static boolean shouldRunInsideGuest(String executable) {
        if (executable == null || executable.isEmpty())
            return false;
        if (!executable.startsWith("/"))
            return false;
        String path = executable + "/";
        return !path.startsWith("/data/data/")
            && !path.startsWith("/system/")
            && !path.startsWith("/vendor/")
            && !path.startsWith("/product/")
            && !path.startsWith("/odm/")
            && !path.startsWith("/apex/")
            && !path.startsWith("/sbin/")
            && !path.startsWith("/debug_ramdisk/")
            && !path.startsWith("/linkerconfig/");
    }
}
