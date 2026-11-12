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
| Shared storage is limited to the application specific external files directory | `MANAGE_EXTERNAL_STORAGE` is deliberately **not** requested. `/sdcard` inside the guest points to `/storage/emulated/0/Android/data/com.ubutumerm.app/files` |
| The host prefix is not visible inside the guest | Mixing Bionic binaries and libraries into a glibc system breaks the dynamic linker |
| The host `apt` repositories are disabled in the host prefix | The official Termux packages are compiled for `/data/data/com.termux/files/usr` and would be unusable. The host prefix only provides the local repository that was built together with the bootstrap |
| The Termux:API methods that need dangerous permissions are not exposed | Camera, SMS, contacts, call log, location, microphone, USB, NFC and fingerprint are not part of the embedded implementation |
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
