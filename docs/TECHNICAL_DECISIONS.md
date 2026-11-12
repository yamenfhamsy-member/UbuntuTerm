# Technical decisions

This document records the decisions that shape UbuntuTerm and the reason behind each of them.

## 1. Fork of Termux instead of a new application

The terminal emulator, the extra keys, the session handling, the terminal view and the whole
"feel" of Termux are the parts users care about most. Re-implementing them would be wasteful
and would break compatibility with the Termux ecosystem (plugins, `termux.properties`,
`termux-api` behaviour).

**Decision:** UbuntuTerm is a fork of `termux/termux-app`
(`yamenfhamsy-member/UbuntuTerm`, upstream commit `8629e632fcb95da272221be327db653fb24befe9`).
The Java package name stays `com.termux` (that keeps the diff small and reviewable), while the
**application id** is `com.ubutumerm.app`. Everything that is visible to Android (the
application id, the launcher label, the notification channel, the shared preferences file
name, the `RUN_COMMAND` permission, the content provider authorities) is derived from
`TermuxConstants.TERMUX_PACKAGE_NAME`, which was changed to `com.ubutumerm.app`.

## 2. The bootstrap is recompiled, not patched

The Termux bootstrap contains binaries whose `RUNPATH`, shebangs and compiled in paths point
to `/data/data/com.termux/files/usr`. Simply renaming the application would break every
binary of the host prefix.

**Alternatives considered**

| Option | Result |
|---|---|
| Use the upstream bootstrap unchanged | Broken: the host binaries look for the wrong prefix |
| Patch the prefix inside the released bootstrap zip | Fragile: paths are compiled into ELF binaries, cannot be fixed reliably |
| Rebuild the bootstrap from `termux-packages` with our package name | **Chosen**: correct, reproducible, and it allows adding PRoot |

**Decision:** the bootstrap is compiled from `termux-packages` (pinned commit
`b189a79f3b9606b108b45460a4c50756d91e2b9d`) with `patches/termux-packages-ubutumerm.patch`,
which sets `TERMUX_APP__PACKAGE_NAME="com.ubutumerr.app"`, adds `proot` to the bootstrap and
disables the official Termux apt repositories (their packages are built for the
`com.termux` prefix and would be unusable). The build happens inside the official
`ghcr.io/termux/package-builder` container, exactly like the upstream bootstrap release.

## 3. PRoot as the Linux engine

PRoot is the only way to run a glibc distribution on Android without root. It translates
path accesses with `ptrace`, so no kernel feature (namespaces, `chroot`, capabilities) is
needed.

**Decision:** use the Android/Bionic PRoot of `termux-packages` with the option set of
`proot-distro` (`-0 --kill-on-exit --link2symlink --sysvipc -L`). The guest is started through
`/usr/bin/env -i` so that the guest environment is completely independent from the host
environment.

## 4. The Ubuntu rootfs is bundled in the APK *and* downloadable

The original specification required the rootfs to be downloaded on first start. The user
explicitly asked for a ready made, compressed distribution inside the application so that the
first start works immediately.

**Decision:** both are implemented.

* **Bundled (default):** the official image is downloaded during the CI build, verified
  against the pinned official SHA-256, converted to an xz compressed tar and stored in
  `app/src/main/assets/ubuntu/`. On the first start the archive is copied out of the APK,
  verified (asset checksum), decompressed with the host `xz` and verified again (tar
  checksum) before it is extracted.
* **Download (fallback):** if the bundled archive is missing, the official image is downloaded
  from `cdimage.ubuntu.com` and verified against the SHA-256 that is pinned in the source code
  (`UbuntuConstants.ROOTFS_SHA256`).

The manifest of the bundled archive (`rootfs-manifest.properties`) records the provenance
(the official URL and its official SHA-256); the application refuses the bundle when the
provenance does not match the pinned official checksum.

## 5. Ubuntu base image instead of a prebuilt image

`ubuntu-base` is the official minimal rootfs of Ubuntu. It contains a working `apt`, `dpkg`
and a shell, it is published with a `SHA256SUMS` file, and it is small enough to be bundled.

**Decision:** `ubuntu-base-26.04.1-base-arm64.tar.gz` (arm64, matching the `arm64-v8a` ABI).

## 6. Extraction is implemented in Java instead of calling `tar`

`tar` is available in the host prefix, but the extraction of an archive that becomes the
root of a Linux system is a security critical operation.

**Decision:** `SafeTarExtractor` parses the tar stream itself and enforces:

* no absolute member paths,
* no `..` path components,
* the real path of the parent directory of every written file must stay inside the extraction
  root (prevents symlink redirection attacks),
* symbolic links must be relative and must not escape the root,
* hard links must point inside the root.

## 7. The installation is atomic

A failed installation must never destroy a working environment.

**Decision:** the image is always extracted into `rootfs-staging`, the guest is prepared
there and only then the directory is swapped with the live rootfs. The previous installation
is only deleted after the swap succeeded and is restored if the swap failed.

## 8. User data is kept outside of the rootfs

**Decision:** `/root` inside the guest is a bind mount of the application home directory
(`/data/data/com.ubutumerm.app/files/home`). The home directory is part of the application
data and is therefore preserved when the APK is updated. The rootfs itself can be replaced
independently (for example when a new Ubuntu release is bundled) without losing the user's
files.

## 9. Termux:API is embedded instead of being a companion application

The official `termux-api` application is a separate APK that shares the UID with Termux. A
second APK would have to be installed by the user and would have to be signed with the same
key, which conflicts with the requirement of a single APK.

**Decision:** the `TermuxApiReceiver`, the socket listener and the API classes are compiled
into the main application. The protocol is unchanged, so the official client would work as
well; a Python re-implementation of the client is used inside the guest, because the official
client is a Bionic binary and cannot run inside the glibc guest.

Only API methods that work without dangerous runtime permissions are exposed (no camera, no
SMS, no contacts, no location, no call log). See [LIMITATIONS.md](LIMITATIONS.md).

## 10. The build only happens in GitHub Actions

The agent/development machine must not build the APK: the Gradle caches, the Android SDK and
the NDK would exceed the available space.

**Decision:** `.github/workflows/ubutumerm-build.yml` performs every build step: bootstrap
compilation, Ubuntu asset preparation, Gradle build, verification, artifact upload and the
Telegram delivery.

## 11. Actions are pinned to commit SHAs

**Decision:** `actions/checkout`, `actions/setup-java`, `actions/upload-artifact` and
`actions/download-artifact` are referenced by their full commit SHA (with the tag in a
comment), so that the workflow cannot be changed by a moved tag.

## 12. Secrets

* `TELEGRAM_BOT_TOKEN` and `TELEGRAM_CHAT_ID` are repository secrets.
* The GitHub token of the agent is **never** uploaded as a secret; the workflow uses the
  built in `GITHUB_TOKEN` with `contents: read` only.
* Optional release signing material (`UBUNTUTERM_KEYSTORE_BASE64`,
  `UBUNTUTERM_KEYSTORE_PASSWORD`, `UBUNTUTERM_KEY_ALIAS`, `UBUNTUTERM_KEY_PASSWORD`) is
  supported. Without it the APK is a **test build** signed with the public test key that is
  part of the Termux sources.
