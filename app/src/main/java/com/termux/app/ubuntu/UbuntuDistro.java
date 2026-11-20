package com.termux.app.ubuntu;

import com.termux.shared.termux.TermuxConstants;

/**
 * Ubuntu guest distribution (the default guest of UbuntuTerm).
 */
public class UbuntuDistro implements GuestDistro {

    /** Ubuntu release shipped by UbuntuTerm. */
    public static final String UBUNTU_VERSION = "26.04.1";

    /** Ubuntu release codename. */
    public static final String UBUNTU_CODENAME = "resolute";

    /** Ubuntu architecture (matches the arm64-v8a Android ABI). */
    public static final String UBUNTU_ARCH = "arm64";

    /** Official Ubuntu base image (minimal arm64 rootfs). */
    public static final String ROOTFS_URL =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/26.04.1/release/ubuntu-base-26.04.1-base-arm64.tar.gz";

    /** SHA-256 of {@link #ROOTFS_URL}. */
    public static final String ROOTFS_SHA256 =
        "5a1906794ced63a71a8119c3f211ef5f0bbe0a243001b4bbd41fdf80c5b219fd";

    /** Telegram channel of the project. */
    public static final String TELEGRAM_CHANNEL = "https://t.me/thorfin963";

    /** Telegram account of the developer. */
    public static final String DEVELOPER_ACCOUNT = "https://t.me/Ya_ali963";

    @Override
    public String id() {
        return "ubuntu";
    }

    @Override
    public String name() {
        return "Ubuntu";
    }

    @Override
    public String version() {
        return UBUNTU_VERSION;
    }

    @Override
    public String codename() {
        return UBUNTU_CODENAME;
    }

    @Override
    public String arch() {
        return UBUNTU_ARCH;
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
        return "ubuntu-base-" + UBUNTU_VERSION + "-base-arm64.tar.gz";
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
        return TermuxConstants.TERMUX_FILES_DIR_PATH + "/ubuntu";
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
        return "ubuntu-base-" + UBUNTU_VERSION + "-base-arm64.tar.xz";
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
            "    printf 'UbuntuTerm " + UBUNTU_VERSION + " - Ubuntu " + UBUNTU_VERSION + " LTS (" +
            UBUNTU_CODENAME + ") " + UBUNTU_ARCH + "\\n'\n" +
            "    printf 'حقوق النشر والمصادر:\\n'\n" +
            "    printf '  قناة تليغرام: " + TELEGRAM_CHANNEL + "\\n'\n" +
            "    printf '  حساب المطور: " + DEVELOPER_ACCOUNT + "\\n'\n" +
            "    printf '\\n'\n" +
            "fi\n";
    }

    @Override
    public String packageSources() {
        String codename = UBUNTU_CODENAME;
        return "# UbuntuTerm configured ports repository for " + UBUNTU_ARCH + "\n" +
            "Types: deb\n" +
            "URIs: http://ports.ubuntu.com/ubuntu-ports/\n" +
            "Suites: " + codename + " " + codename + "-updates " + codename + "-backports\n" +
            "Components: main universe restricted multiverse\n" +
            "Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg\n\n" +
            "Types: deb\n" +
            "URIs: http://ports.ubuntu.com/ubuntu-ports/\n" +
            "Suites: " + codename + "-security\n" +
            "Components: main universe restricted multiverse\n" +
            "Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg\n";
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

    private UbuntuDistro() {
    }

    /** The default distro of UbuntuTerm. */
    public static final GuestDistro UBUNTU = new UbuntuDistro();
}
