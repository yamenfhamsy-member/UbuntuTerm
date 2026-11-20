package com.termux.app.ubuntu;

/**
 * Describes a Linux distribution that can run as a PRoot guest inside UbuntuTerm.
 *
 * <p>Each distro defines its own rootfs image, package manager, and guest setup steps.
 * UbuntuTerm keeps the Ubuntu guest as the default and can be extended to Arch and Kali.
 */
public interface GuestDistro {

    /** Internal identifier used in directory names and stamp files. */
    String id();

    /** Human readable name shown in the UI. */
    String name();

    /** Release version. */
    String version();

    /** Release codename. */
    String codename();

    /** Architecture (matches the arm64-v8a Android ABI). */
    String arch();

    /** Official base image URL (minimal arm64 rootfs). */
    String rootfsUrl();

    /** SHA-256 of {@link #rootfsUrl()}. */
    String rootfsSha256();

    /** File name of the downloaded image inside the download cache. */
    String rootfsFileName();

    /** Package manager used inside the guest ("apt" or "pacman"). */
    String packageManager();

    /** Guest side login shell. */
    String guestShell();

    /** Guest side home directory (mapped to the app home so data survives updates). */
    String guestHome();

    /** Guest side PATH. */
    String guestPath();

    /** Directory holding all host side state for this distro. */
    String distroDir();

    /** Directory the verified rootfs is installed into. */
    String rootfsDir();

    /** Directory used while extracting a new rootfs (atomic installation). */
    String stagingDir();

    /** Directory used for downloads. */
    String downloadDir();

    /** File describing the installed rootfs (version, checksum, install time). */
    String stampFile();

    /** Marker file written inside the rootfs once the guest is prepared. */
    String guestMarkerFile();

    /** Name of the compressed rootfs archive bundled inside the APK. */
    String bundledRootfsAsset();

    /** Fallback DNS servers used when the host resolver configuration cannot be read. */
    String fallbackResolvConf();

    /** Build the copyright notice printed for every new guest session. */
    String buildCreditNotice();

    /** Sources configuration for the guest package manager. */
    String packageSources();

    /** APT configuration for PRoot (sandbox disabled). */
    String aptConfig();

    /** Pacman configuration for PRoot. */
    String pacmanConfig();

}
