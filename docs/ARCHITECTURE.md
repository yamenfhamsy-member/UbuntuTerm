# Architecture

UbuntuTerm consists of four clearly separated layers. The separation is the most important
design rule of the project: **Android/Bionic never leaks into the Ubuntu/glibc guest and
Ubuntu/glibc never replaces the Android runtime.**

```
┌───────────────────────────────────────────────────────────────────────┐
│ A. Android host runtime (Bionic, app process)                         │
│                                                                       │
│  TermuxActivity / TerminalView / TerminalSession (upstream Termux)    │
│  TermuxService          – starts sessions, keeps the PTY              │
│  UbuntuRootFsManager    – provisions and verifies the Ubuntu rootfs   │
│  SafeTarExtractor       – hardened tar extraction                     │
│  UbuntuSessionLauncher  – builds the PRoot command line               │
│  UbuntuInstallerActivity– first run installation UI                   │
│  com.termux.api.*       – embedded Termux:API (receiver + socket)    │
│                                                                       │
│  Host prefix ($PREFIX = /data/data/com.ubutumerm.app/files/usr)       │
│  contains the Termux bootstrap: bash, coreutils, apt, dpkg, proot …   │
└───────────────────────────────┬───────────────────────────────────────┘
                                │ execve("/data/data/com.ubutumerm.app/files/usr/bin/proot", …)
┌───────────────────────────────▼───────────────────────────────────────┐
│ C. PRoot runtime (Android/Bionic PRoot, termux/proot 5.1.107.102)     │
│                                                                       │
│  -0 --kill-on-exit --link2symlink --sysvipc -L                        │
│  --rootfs=<rootfs> --cwd=/root                                        │
│  --bind=/dev --bind=/proc --bind=/sys                                 │
│  --bind=<app home>:/root --bind=<external files>:/sdcard              │
└───────────────────────────────┬───────────────────────────────────────┘
                                │ ptrace based path translation
┌───────────────────────────────▼───────────────────────────────────────┐
│ B. Ubuntu guest environment (glibc, Ubuntu 26.04.1 LTS arm64)         │
│                                                                       │
│  /bin/bash, apt, dpkg, coreutils, python3, git, curl, gcc …          │
│  /root            → bound to the application home directory            │
│  /usr/local/bin   → Termux:API command wrappers (termux-toast, …)     │
│  /usr/local/lib/ubuntukerm/termux-api → Python API client             │
└───────────────────────────────┬───────────────────────────────────────┘
                                │ abstract UNIX socket (same network namespace)
┌───────────────────────────────▼───────────────────────────────────────┐
│ D. Android API bridge                                                 │
│                                                                       │
│  guest:  termux-toast ──► termux-api (Python)                        │
│          two abstract sockets (result + stdin), length prefixed cmd   │
│                                                                       │
│  host:   SocketListener ──► TermuxApiReceiver ──► *API classes       │
│          (com.ubutumerm.app.api://listen)                             │
└───────────────────────────────────────────────────────────────────────┘
```

## A. Android host runtime

The host runtime is the upstream Termux application with three additions:

* `com.termux.app.ubuntu.*` – the Ubuntu guest management.
* `com.termux.api.*` – the Termux:API implementation (ported from
  [termux/termux-api](https://github.com/termux/termux-api)).
* The bootstrap is **recompiled** for the `com.ubutumerm.app` package name, because the
  Termux packages hard code `/data/data/com.termux/files/usr` into their binaries, shebangs
  and `RUNPATH` entries.

The host prefix keeps the Bionic toolchain (`bash`, `coreutils`, `apt`, `dpkg`, `xz`,
`proot`, …). It is *never* exposed inside the guest.

## B. Ubuntu guest environment

* Rootfs: `ubuntu-base-26.04.1-base-arm64.tar.gz` from the official Ubuntu image server
  (`https://cdimage.ubuntu.com/ubuntu-base/releases/26.04.1/release/`).
* Verified against the pinned SHA-256
  `5a1906794ced63a71a8119c3f211ef5f0bbe0a243001b4bbd41fdf80c5b219fd` before it is unpacked.
* Installed into `/data/data/com.ubutumerm.app/files/ubuntu/rootfs`.
* The guest home directory `/root` is a bind mount of
  `/data/data/com.ubutumerm.app/files/home`, therefore user data survives rootfs updates and
  APK updates.
* The guest environment is created from scratch with `env -i`, so no host variable
  (`PREFIX`, `LD_LIBRARY_PATH`, …) is visible inside Ubuntu.

## C. PRoot runtime

PRoot is the Android/Bionic build of `termux/proot` that is part of the compiled bootstrap.
It is started with the same option set that `proot-distro` uses for a normal (non Termux)
container:

```
proot -0 --kill-on-exit --link2symlink --sysvipc -L \
       --kernel-release=… \
       --rootfs=<rootfs> --cwd=/root \
       --bind=/dev --bind=/proc --bind=/sys --bind=/dev/urandom:/dev/random \
       --bind=<app home>:/root --bind=<external files>:/sdcard \
       /usr/bin/env -i HOME=/root PATH=… TERM=xterm-256color SHELL=/bin/bash … \
       /bin/bash --login
```

`env -i` is the boundary: everything after it belongs to the guest.

## D. Android API bridge

The bridge re-uses the **official Termux:API protocol** so that the behaviour and the
command line interface stay compatible:

1. The guest command (for example `termux-toast`) calls
   `/usr/local/lib/ubuntukerm/termux-api`, a Python implementation of the official
   `termux-api` client.
2. The client creates two abstract UNIX sockets (one for the result, one for stdin) and
   connects to the abstract socket `com.ubutumerm.app.api://listen` of the application.
3. It sends a length prefixed command line that mimics an `am broadcast` invocation:
   `--es socket_input "…" --es socket_output "…" --ei api_server_pid … --es api_method "…"`.
4. `SocketListener` parses the command line, dispatches an ordered broadcast to
   `TermuxApiReceiver` and acknowledges with a single NUL byte.
5. The API implementation writes the result into the result socket, the client copies it to
   stdout and exits with the exit code of the API call.

Because abstract UNIX sockets live in the network namespace and PRoot does not create a new
one, the guest can reach the host socket directly. The application checks the peer UID, so
only processes of the application itself can use the bridge.

## Data flow of the first start

```
launcher → TermuxActivity → TermuxService → bootstrap installed (first start only)
         → Ubuntu guest missing? → UbuntuInstallerActivity
             → copy the bundled archive from the APK assets
             → verify SHA-256 (asset) → decompress with xz → verify SHA-256 (tar)
             → extract into a staging directory → prepare the guest
             → atomic swap → write the stamp file
         → back to TermuxActivity → new session → PRoot → Ubuntu login shell
```
