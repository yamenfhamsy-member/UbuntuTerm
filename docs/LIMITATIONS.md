# Limitations

UbuntuTerm runs a full Ubuntu userspace on Android **without root**. PRoot provides the
compatibility layer, and that has consequences. The following features are known to be
unavailable or restricted.

## Not available inside the guest

| Feature | Reason |
|---|---|
| `systemd` as init system | No real init, no cgroups, no namespaces. Use `systemctl` only for units that do not need the kernel |
| Kernel modules (`modprobe`) | Loading kernel modules requires real root privileges |
| Real `chroot` | PRoot emulates the file system view, it does not provide a new root |
| Mount namespaces / user namespaces | Not available to an unprivileged Android application |
| `cgroups` (systemd slices, docker) | No access to the cgroup file system |
| Docker daemon | Requires cgroups, namespaces and capabilities that are not available |
| Real capabilities (`CAP_SYS_ADMIN`, …) | PRoot only fakes the credentials for the emulated file system |
| `iptables` / `nftables` | Requires real root privileges inside the kernel |
| Raw sockets / `ping` with raw ICMP | Requires `CAP_NET_RAW` |
| Kernel version detection | The kernel release is faked by PRoot (`--kernel-release`), so `uname -r` does not show the real device kernel |
| Hardware acceleration (GPU, hardware video decoding) | The guest has no access to the Android graphics stack |

## Restrictions of the application

| Restriction | Reason |
|---|---|
| Shared storage needs an explicit user grant | The guest can mount the shared storage only after the user grants the storage permission: `READ`/`WRITE_EXTERNAL_STORAGE` on Android 10 and below, or `MANAGE_EXTERNAL_STORAGE` ("All files access") on Android 11 and above. Without the permission `/sdcard` inside the guest falls back to `/storage/emulated/0/Android/data/com.ubutumerm.app/files` |
| The storage permission is requested only when the user asks for it | `MANAGE_EXTERNAL_STORAGE` is a sensitive permission, so it is never requested silently: the "Storage access" entry of the context menu explains the options and opens the system settings |
| The host prefix is not visible inside the guest | Mixing Bionic binaries and libraries into a glibc system breaks the dynamic linker |
| The host `apt` repositories are disabled in the host prefix | The official Termux packages are compiled for `/data/data/com.termux/files/usr` and would be unusable. The host prefix only provides the local repository that was built together with the bootstrap (its index is created by the installer, so `apt update` works in the host mode) |
| The Termux:API methods that need dangerous runtime permissions are not exposed | SMS, contacts, call log, location, microphone, USB, NFC and fingerprint: their wrappers were removed because no handler exists. The torch works after the camera permission is granted, the SSID of the Wi-Fi info is only reported after the location permission is granted |
| The notification listener (Termux:API `NotificationList`) is not available | It requires a separate notification listener service |
| No `Termux:Boot`, `Termux:Tasker`, `Termux:Widget` add-ons | They are separate applications that would have to be signed with the same key; the embedded API covers the functionality that is needed inside Ubuntu |
| The Termux bootstrap is not part of the APK | it has to match the application id of the fork, so the user imports `bootstrap-aarch64.zip` from the storage of the device with the import screen of the application |
| The APK is signed with a test key unless release secrets are configured | A release key must never be committed to the repository |

## Behavioural notes

* PRoot is significantly slower than native execution: heavy build systems inside the guest
  can be an order of magnitude slower than on a desktop.
* The guest sees the host `/proc`, so `ps` shows the processes of the device.
* The guest time is the host time, no time namespace is used.
* Updating the application never deletes the rootfs, the home directory or the installed
  packages. A new Ubuntu release is installed by the application itself (or by clearing the
  rootfs directory), not by an APK update.
* The storage links below `~/storage` are repaired at every session start. A link that points to
  a wrong target is replaced, but a real directory or file is never deleted: the link is then
  skipped and the problem is reported in the log.
* The bootstrap archive is verified against the published SHA-256 checksum. An archive with a
  different checksum can still be installed, but only after an explicit confirmation that shows
  the calculated checksum.
* The guest package sources are rewritten to `ports.ubuntu.com/ubuntu-ports` at every session
  start. The mirrors of the base image do not serve the packages of the arm64 architecture, so
  `apt update` failed with `404` before the rewrite.
* The package manager runs with the sandbox user of APT disabled (`APT::Sandbox::User "root"`),
  because the file system of the guest is owned by the application and `_apt` cannot read it.
* The hard links of the base image are extracted as hard links. When the file system of the
  device refuses a hard link, a relative symbolic link to the same file is created; the file is
  copied only when the device supports neither. Without this fallback the more than one hundred
  hard links of the core utilities would need more than a gigabyte of additional space.
* The temporary directory of the guest is a bind mount of the temporary directory of the
  application: the guest writes its temporary files outside of the rootfs, so downloading and
  unpacking packages does not consume the space of the rootfs twice.
