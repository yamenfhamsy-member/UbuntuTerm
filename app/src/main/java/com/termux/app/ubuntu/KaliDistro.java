package com.termux.app.ubuntu;

import com.termux.shared.termux.TermuxConstants;

/**
 * Kali Linux guest distribution.
 *
 * <p>Kali Linux provides official arm64 rootfs images and uses {@code apt} as its package
 * manager. The checksum below is a placeholder and must be verified against the official
 * Kali image server before shipping.
 */
public class KaliDistro implements GuestDistro {

    /** Kali Linux release version. */
    public static final String KALI_VERSION = "2024.2";

    /** Kali Linux codename. */
    public static final String KALI_CODENAME = "kali-rolling";

    /** Kali Linux architecture. */
    public static final String KALI_ARCH = "arm64";

    /** Official Kali Linux base image (minimal arm64 rootfs). */
    public static final String ROOTFS_URL =
        "https://kali.download/kali-images/kali-linux-2024.2/kali-linux-2024.2-arm64.tar.xz";

    /** SHA-256 of {@link #ROOTFS_URL} - placeholder, verify before shipping. */
    public static final String ROOTFS_SHA256 =
        "0000000000000000000000000000000000000000000000000000000000000000";

    @Override
    public String id() {
        return "kali";
    }

    @Override
    public String name() {
        return "Kali Linux";
    }

    @Override
    public String version() {
        return KALI_VERSION;
    }

    @Override
    public String codename() {
        return KALI_CODENAME;
    }

    @Override
    public String arch() {
        return KALI_ARCH;
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
        return "kali-linux-" + KALI_VERSION + "-arm64.tar.xz";
    }

    @Override
    public String packageManager() {
        return "apt";
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
        return TermuxConstants.TERMUX_FILES_DIR_PATH + "/kali";
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
            "    printf 'UbuntuTerm - Kali Linux " + KALI_VERSION + " " + KALI_ARCH + "\\n'\n" +
            "    printf 'حقوق النشر والمصادر:\\n'\n" +
            "    printf '  قناة تليغرام: https://t.me/thorfin963\\n'\n" +
            "    printf '  حساب المطور: https://t.me/Ya_ali963\\n'\n" +
            "    printf '\\n'\n" +
            "fi\n";
    }

    @Override
    public String packageSources() {
        return "# Kali Linux repositories\n" +
            "deb http://http.kali.org/kali " + KALI_CODENAME + " main non-free contrib\n" +
            "deb http://http.kali.org/kali " + KALI_CODENAME + "-updates main non-free contrib\n";
    }

    @Override
    public String aptConfig() {
        return "APT::Sandbox::User \"root\";\n" +
            "Acquire::Languages \"none\";\n" +
            "Dpkg::Options {\n" +
            "   \"--force-confdef\";\n" +
            "   \"--force-confold\";\n" +
            "};\n";
    }

    @Override
    public String pacmanConfig() {
        return "";
    }

    private KaliDistro() {
    }

    public static final GuestDistro KALI = new KaliDistro();
}
