package com.termux.app.ubuntu;

import com.termux.shared.termux.TermuxConstants;

/**
 * Constants describing the Ubuntu guest environment handled by UbuntuTerm.
 *
 * <p>The Ubuntu guest is a userspace (glibc) system executed through PRoot on top of the
 * Android/Bionic host runtime. The rootfs is <b>not</b> shipped inside the APK, it is downloaded
 * from the official Ubuntu image server on first use and verified against a pinned SHA-256.
 */
public class UbuntuConstants {

    /** Ubuntu release shipped by UbuntuTerm. */
    public static final String UBUNTU_VERSION = "26.04.1";

    /** Ubuntu release codename. */
    public static final String UBUNTU_CODENAME = "resolute";

    /** Ubuntu architecture (matches the arm64-v8a Android ABI). */
    public static final String UBUNTU_ARCH = "arm64";

    /** Official Ubuntu base image (minimal arm64 rootfs). */
    public static final String ROOTFS_URL =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/26.04.1/release/ubuntu-base-26.04.1-base-arm64.tar.gz";

    /**
     * SHA-256 of {@link #ROOTFS_URL}.
     * Source: https://cdimage.ubuntu.com/ubuntu-base/releases/26.04.1/release/SHA256SUMS
     */
    public static final String ROOTFS_SHA256 =
        "5a1906794ced63a71a8119c3f211ef5f0bbe0a243001b4bbd41fdf80c5b219fd";

    /** File name of the downloaded image inside the download cache. */
    public static final String ROOTFS_FILE_NAME = "ubuntu-base-" + UBUNTU_VERSION + "-base-arm64.tar.gz";

    /** Directory holding all UbuntuTerm specific host side state. */
    public static final String UBUNTU_TERM_DIR = TermuxConstants.TERMUX_FILES_DIR_PATH + "/ubuntu";

    /** Directory the verified rootfs is installed into. */
    public static final String ROOTFS_DIR = UBUNTU_TERM_DIR + "/rootfs";

    /** Directory used while extracting a new rootfs (atomic installation). */
    public static final String STAGING_DIR = UBUNTU_TERM_DIR + "/rootfs-staging";

    /** Directory used for downloads. */
    public static final String DOWNLOAD_DIR = UBUNTU_TERM_DIR + "/download";

    /** File describing the installed rootfs (version, checksum, install time). */
    public static final String STAMP_FILE = UBUNTU_TERM_DIR + "/rootfs.stamp";

    /** Marker file written inside the rootfs once the guest is prepared. */
    public static final String GUEST_MARKER_FILE = ROOTFS_DIR + "/.ubutumerm-prepared";

    /** Directory inside the guest holding the Termux:API client wrappers. */
    public static final String GUEST_BIN_DIR = ROOTFS_DIR + "/usr/local/bin";

    /** Directory inside the guest holding the Termux:API client implementation. */
    public static final String GUEST_LIB_DIR = ROOTFS_DIR + "/usr/local/lib/ubutumerm";

    /** Path of the host side PRoot binary (part of the Termux bootstrap). */
    public static final String PROOT_BINARY = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/proot";

    /** Path of the host side tar binary (part of the Termux bootstrap). */
    public static final String HOST_TAR_BINARY = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/tar";

    /** Path of the host side xz binary (part of the Termux bootstrap), used to unpack the bundled rootfs. */
    public static final String HOST_XZ_BINARY = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/xz";

    /** Name of the compressed Ubuntu archive bundled inside the APK. */
    public static final String BUNDLED_ROOTFS_ASSET = "ubuntu-base-" + UBUNTU_VERSION + "-base-arm64.tar.xz";

    /** Abstract UNIX socket the guest side client connects to. */
    public static final String API_LISTEN_ADDRESS = TermuxConstants.TERMUX_API_PACKAGE_NAME + "://listen";

    /** Guest side PATH. */
    public static final String GUEST_PATH =
        "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";

    /** Guest side login shell. */
    public static final String GUEST_SHELL = "/bin/bash";

    /** Guest side home directory (mapped to the app home so that data survives APK updates). */
    public static final String GUEST_HOME = "/root";

    /** Fallback DNS servers used when the host resolver configuration cannot be read. */
    public static final String FALLBACK_RESOLV_CONF =
        "nameserver 1.1.1.1\nnameserver 8.8.8.8\n";

    /** Telegram channel of the project. */
    public static final String TELEGRAM_CHANNEL = "https://t.me/thorfin963";

    /** Telegram account of the developer. */
    public static final String DEVELOPER_ACCOUNT = "https://t.me/Ya_ali963";

    /**
     * Build the copyright notice that is printed every time the user starts a new session
     * inside the Ubuntu guest.
     */
    public static String buildCreditNotice() {
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

    private UbuntuConstants() {
    }
}
