package com.termux.app.ubuntu;

import com.termux.shared.termux.TermuxConstants;

/**
 * Constants describing the Ubuntu guest environment handled by UbuntuTerm.
 *
 * <p>All constants now delegate to {@link UbuntuDistro#UBUNTU} so that existing code keeps
 * working while the codebase supports multiple distributions (Ubuntu, Arch, Kali).
 */
public class UbuntuConstants {

    /** The default distro of UbuntuTerm (Ubuntu). */
    public static final GuestDistro DISTRO = UbuntuDistro.UBUNTU;

    /** Ubuntu release shipped by UbuntuTerm. */
    public static final String UBUNTU_VERSION = DISTRO.version();

    /** Ubuntu release codename. */
    public static final String UBUNTU_CODENAME = DISTRO.codename();

    /** Ubuntu architecture (matches the arm64-v8a Android ABI). */
    public static final String UBUNTU_ARCH = DISTRO.arch();

    /** Official Ubuntu base image (minimal arm64 rootfs). */
    public static final String ROOTFS_URL = DISTRO.rootfsUrl();

    /**
     * SHA-256 of {@link #ROOTFS_URL}.
     * Source: https://cdimage.ubuntu.com/ubuntu-base/releases/26.04.1/release/SHA256SUMS
     */
    public static final String ROOTFS_SHA256 = DISTRO.rootfsSha256();

    /** File name of the downloaded image inside the download cache. */
    public static final String ROOTFS_FILE_NAME = DISTRO.rootfsFileName();

    /** Directory holding all UbuntuTerm specific host side state. */
    public static final String UBUNTU_TERM_DIR = DISTRO.distroDir();

    /** Directory the verified rootfs is installed into. */
    public static final String ROOTFS_DIR = DISTRO.rootfsDir();

    /** Directory used while extracting a new rootfs (atomic installation). */
    public static final String STAGING_DIR = DISTRO.stagingDir();

    /** Directory used for downloads. */
    public static final String DOWNLOAD_DIR = DISTRO.downloadDir();

    /** File describing the installed rootfs (version, checksum, install time). */
    public static final String STAMP_FILE = DISTRO.stampFile();

    /** Marker file written inside the rootfs once the guest is prepared. */
    public static final String GUEST_MARKER_FILE = DISTRO.guestMarkerFile();

    /** Directory inside the guest holding the Termux:API client wrappers. */
    public static final String GUEST_BIN_DIR = ROOTFS_DIR + "/usr/local/bin";

    /** Directory inside the guest holding the Termux:API client implementation. */
    public static final String GUEST_LIB_DIR = ROOTFS_DIR + "/usr/local/lib/ubutumerm";

    /** Path of the host side PRoot binary (part of the Termux bootstrap). */
    public static final String PROOT_BINARY = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/proot";

    /** Path of the host side tar binary (part of the Termux bootstrap). */
    public static final String HOST_TAR_BINARY = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/tar";

    /** Name of the compressed Ubuntu archive bundled inside the APK. */
    public static final String BUNDLED_ROOTFS_ASSET = DISTRO.bundledRootfsAsset();

    /** Abstract UNIX socket the guest side client connects to. */
    public static final String API_LISTEN_ADDRESS = TermuxConstants.TERMUX_API_PACKAGE_NAME + "://listen";

    /** Guest side PATH. */
    public static final String GUEST_PATH = DISTRO.guestPath();

    /** Guest side login shell. */
    public static final String GUEST_SHELL = DISTRO.guestShell();

    /** Guest side home directory (mapped to the app home so that data survives APK updates). */
    public static final String GUEST_HOME = DISTRO.guestHome();

    /** Fallback DNS servers used when the host resolver configuration cannot be read. */
    public static final String FALLBACK_RESOLV_CONF = DISTRO.fallbackResolvConf();

    /** Telegram channel of the project. */
    public static final String TELEGRAM_CHANNEL = "https://t.me/thorfin963";

    /** Telegram account of the developer. */
    public static final String DEVELOPER_ACCOUNT = "https://t.me/Ya_ali963";

    /**
     * Build the copyright notice that is printed every time the user starts a new session
     * inside the Ubuntu guest.
     */
    public static String buildCreditNotice() {
        return DISTRO.buildCreditNotice();
    }

    private UbuntuConstants() {
    }
}
