package com.termux.app.ubuntu;

import com.termux.shared.termux.TermuxConstants;

/**
 * Arch Linux ARM guest distribution.
 *
 * <p>Arch Linux ARM provides a minimal arm64 rootfs and uses {@code pacman} as its package
 * manager. The checksum below is a placeholder and must be verified against the official
 * Arch Linux ARM image server before shipping.
 */
public class ArchDistro implements GuestDistro {

    /** Arch Linux ARM release version. */
    public static final String ARCH_VERSION = "latest";

    /** Arch Linux ARM codename (Arch is rolling, no codename). */
    public static final String ARCH_CODENAME = "rolling";

    /** Arch Linux ARM architecture. */
    public static final String ARCH_ARCH = "arm64";

    /** Official Arch Linux ARM base image (minimal arm64 rootfs). */
    public static final String ROOTFS_URL =
        "http://archlinuxarm.org/os/ArchLinuxARM-arm64-latest.tar.gz";

    /** SHA-256 of {@link #ROOTFS_URL} - placeholder, verify before shipping. */
    public static final String ROOTFS_SHA256 =
        "0000000000000000000000000000000000000000000000000000000000000000";

    @Override
    public String id() {
        return "arch";
    }

    @Override
    public String name() {
        return "Arch Linux";
    }

    @Override
    public String version() {
        return ARCH_VERSION;
    }

    @Override
    public String codename() {
        return ARCH_CODENAME;
    }

    @Override
    public String arch() {
        return ARCH_ARCH;
    }

    @Override
    public String rootfsUrl() {
        return ROOTFS_URL;
    }

    @Override
    public String rootfsSha256() {
        return ROOTFS_SHA256;
    }

    @Override
    public String rootfsFileName() {
        return "ArchLinuxARM-" + ARCH_ARCH + "-latest.tar.gz";
    }

    @Override
    public String packageManager() {
        return "pacman";
    }

    @Override
    public String guestShell() {
        return "/bin/bash";
    }

    @Override
    public String guestHome() {
        return "/root";
    }

    @Override
    public String guestPath() {
        return "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";
    }

    @Override
    public String distroDir() {
        return TermuxConstants.TERMUX_FILES_DIR_PATH + "/arch";
    }

    @Override
    public String rootfsDir() {
        return distroDir() + "/rootfs";
    }

    @Override
    public String stagingDir() {
        return distroDir() + "/rootfs-staging";
    }

    @Override
    public String downloadDir() {
        return distroDir() + "/download";
    }

    @Override
    public String stampFile() {
        return distroDir() + "/rootfs.stamp";
    }

    @Override
    public String guestMarkerFile() {
        return rootfsDir() + "/.ubutumerm-prepared";
    }

    @Override
    public String bundledRootfsAsset() {
        return "";
    }

    @Override
    public String fallbackResolvConf() {
        return "nameserver 1.1.1.1\nnameserver 8.8.8.8\n";
    }

    @Override
    public String buildCreditNotice() {
        return "#!/bin/sh\n" +
            "# Copyright notice of UbuntuTerm, printed for every new guest session.\n" +
            "if [ -t 1 ]; then\n" +
            "    printf '\\n'\n" +
            "    printf 'UbuntuTerm - Arch Linux " + ARCH_ARCH + "\\n'\n" +
            "    printf 'حقوق النشر والمصادر:\\n'\n" +
            "    printf '  قناة تليغرام: https://t.me/thorfin963\\n'\n" +
            "    printf '  حساب المطور: https://t.me/Ya_ali963\\n'\n" +
            "    printf '\\n'\n" +
            "fi\n";
    }

    @Override
    public String packageSources() {
        return "# Arch Linux ARM repositories\n" +
            "Server = http://archlinuxarm.org/$arch/$repo\n";
    }

    @Override
    public String aptConfig() {
        return "";
    }

    @Override
    public String pacmanConfig() {
        return "# Arch Linux ARM pacman configuration for PRoot\n" +
            "CacheDir = /var/cache/pacman/pkg\n" +
            "NoExtract = usr/share/man/*\n" +
            "HoldPkg = pacman glibc\n";
    }

    private ArchDistro() {
    }

    public static final GuestDistro ARCH = new ArchDistro();
}
