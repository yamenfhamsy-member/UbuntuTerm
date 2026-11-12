# UbuntuTerm

**UbuntuTerm** is a standalone Android terminal application built on top of
[Termux](https://github.com/termux/termux-app). It keeps the original Termux terminal
experience (sessions, extra keys, copy/paste, colours, fonts, multiple sessions) but makes
**Ubuntu 26.04.1 LTS (Resolute Raccoon)** the default Linux environment of the application.

Everything runs **without root**: the Ubuntu guest is a userspace (glibc) system executed by
[PRoot](https://github.com/termux/proot) on top of the Android/Bionic host runtime.

| Property | Value |
|---|---|
| Application name | UbuntuTerm |
| Package ID | `com.ubunturerm.app` |
| Android ABI | `arm64-v8a` (only) |
| Ubuntu version | 26.04.1 LTS (Resolute Raccoon) |
| Ubuntu architecture | `arm64` |
| Package manager | `apt` / `dpkg` (inside the guest) |
| Linux engine | PRoot (Android/Bionic build from `termux-packages`) |
| Root required | No |
| Terminal UI | Termux terminal emulator (unchanged) |
| Android API | Termux:API embedded into the main APK |
| Build system | GitHub Actions only |
| Output | a single `UbuntuTerm-arm64-v8a.apk` |

---

## What works

* The application starts **directly into Ubuntu**. No `proot-distro login`, no manual
  installation commands, no second application to install.
* The Ubuntu rootfs (Ubuntu 26.04.1 LTS, arm64) is shipped **inside the APK** as an xz
  compressed archive and is unpacked on the first start, so Ubuntu is available immediately
  and offline. If the bundled archive is unavailable the application falls back to
  downloading the official image from `cdimage.ubuntu.com` and verifying it against a pinned
  SHA-256. The archive is decompressed in pure Java, so no tool of the host runtime is needed.
* The **Termux bootstrap is not part of the APK** (it has to match the application id of the
  fork). On the first start the application asks for a `bootstrap-aarch64.zip` file and installs
  the archive that the user selects from the storage of the device into the application prefix
  directory.
* `apt` works inside the guest: `apt-get update`, `apt-get install python3 git curl
  ca-certificates`, `apt-get check`, `dpkg --audit`, `dpkg --print-architecture` → `arm64`.
* User data survives APK updates: the guest home directory is the application home directory
  (`/data/data/com.ubutumerm.app/files/home`), the rootfs lives in
  `/data/data/com.ubutumerm.app/files/ubuntu/rootfs`.
* Termux:API is embedded: `termux-toast`, `termux-battery-status`, `termux-clipboard-get`,
  `termux-clipboard-set` and 50+ other commands work **from inside Ubuntu** through an
  abstract UNIX socket bridge.

## Repository layout

```
app/                     UbuntuTerm application (package id com.ubutumerm.app)
  src/main/java/com/termux/app/ubuntu/   Ubuntu guest runtime (rootfs manager, PRoot, installer UI)
  src/main/java/com/termux/api/          Embedded Termux:API implementation
  src/main/assets/ubuntu/                Guest side Termux:API client and command wrappers
termux-shared/           Shared library (TermuxConstants, shell environment, ...)
terminal-emulator/       Terminal emulator (unchanged upstream)
terminal-view/           Terminal view (unchanged upstream)
patches/                 termux-packages patch used to build the custom bootstrap
.github/workflows/       Build workflow (GitHub Actions is the only build environment)
docs/                    Architecture, decisions, build, testing, limitations, licences
```

## Building

The APK is **only** built by GitHub Actions, see [docs/BUILDING.md](docs/BUILDING.md).

Before a build is started the repository is validated locally or in the pipeline with
`shellcheck`, `actionlint`, `yamllint`, a `javac` type check of every source and Android Lint:

```bash
tools/preflight.sh
```

```bash
git clone https://github.com/yamenfhamsy-member/UbuntuTerm.git
cd UbuntuTerm
# push a commit or run the workflow manually:
gh workflow run ubutumerm-build.yml --repo yamenfhamsy-member/UbuntuTerm
```

The workflow produces one artifact: `UbuntuTerm-arm64-v8a.apk` together with the verification
reports and a `SHA256SUMS.txt`.

## Documentation

| File | Content |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Layers, data flow, IPC protocol |
| [docs/TECHNICAL_DECISIONS.md](docs/TECHNICAL_DECISIONS.md) | Why the project is built the way it is |
| [docs/BUILDING.md](docs/BUILDING.md) | How the APK is built, released and delivered |
| [docs/TESTING.md](docs/TESTING.md) | Test layers, results and how to repeat them |
| [docs/LIMITATIONS.md](docs/LIMITATIONS.md) | What PRoot cannot do |
| [docs/THIRD_PARTY_NOTICES.md](docs/THIRD_PARTY_NOTICES.md) | Licences of all used components |

## Copyright

UbuntuTerm is developed and published by the maintainer of the Telegram channel
**thorfin963** and the developer account **Ya_ali963**:

| | |
|---|---|
| Telegram channel | https://t.me/thorfin963 |
| Developer account | https://t.me/Ya_ali963 |

The copyright notice is printed by every new session inside the Ubuntu guest, so that it is
always visible while the application is used.

## Upstream

UbuntuTerm is a fork of [termux/termux-app](https://github.com/termux/termux-app) at commit
`8629e632fcb95da272221be327db653fb24befe9` (version 0.118.0). The upstream README is kept in
[docs/UPSTREAM-TERMUX-README.md](docs/UPSTREAM-TERMUX-README.md). UbuntuTerm is released under
the same licence as Termux, see [LICENSE.md](LICENSE.md).
