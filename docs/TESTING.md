# Testing

The tests are split into four layers. The result of every layer is recorded in the build
report (`final-output/reports/`) and in the Telegram status message.

## Layer 1 – build tests (executed by GitHub Actions)

| Test | Tool | Expected result |
|---|---|---|
| Gradle build | `./gradlew assembleRelease` | APK is produced |
| Unit tests | `./gradlew testReleaseUnitTest` | all tests pass (the result of every test is printed into the build log) (includes the tar extractor security tests: a symlink with an absolute target is created, a relative target that escapes the root is refused, a hard link to an unsafe path or to a symbolic link is refused, the hard links of the base image are extracted as a link and not as a copy, no member is written outside of the extraction root and the permissions of the archive are applied exactly; the storage link tests: a wrong link is repaired, a real file is never deleted and the shared storage is bound; the bootstrap checksum tests: the published checksum is accepted and a modified checksum is refused; and the bidirectional text tests of the terminal view) |
| Package id | `aapt2 dump badging` | `name='com.ubutumerm.app'` |
| Application label | `aapt2 dump badging` | `application-label:'UbuntuTerm'` |
| Version | `aapt2 dump badging` | `versionCode`/`versionName` are printed into the report |
| ABI | `aapt2 dump badging` + `unzip -l` | only `arm64-v8a`, no `armeabi-v7a`, `x86` or `x86_64` |
| Native libraries | `unzip -l \| grep lib/` | every entry is below `lib/arm64-v8a/` |
| Signature | `apksigner verify --print-certs` | signature is valid |
| Bootstrap isolation | `readelf`/`strings` sweep | no `/data/data/com.termux` reference in the bootstrap |
| Ubuntu asset | `sha256sum` | the archive matches the official Ubuntu checksum |
| Dependencies of the APK | `tools/dex-audit.py` | every class that the application uses is really inside the APK (a library that references the classes of an optional dependency would crash the application on the device) |

## Result of the last build

Workflow run: <https://github.com/yamenfhamsy-member/UbuntuTerm/actions/runs/43340626539>

| Check | Result |
|---|---|
| Pre-flight (shellcheck, actionlint, yamllint, javac type check) | Pass |
| Android Lint (with the baseline of the ported API code) | Pass |
| Gradle build (`testReleaseUnitTest assembleRelease`) | Pass |
| Package id `com.ubutumerm.app` | Pass |
| Application label `UbuntuTerm` | Pass |
| Native libraries only below `lib/arm64-v8a/` | Pass |
| `native-code: 'arm64-v8a'` | Pass |
| APK signature (v2 scheme) | Pass (test key) |
| Bundled Ubuntu rootfs (`assets/ubuntu/ubuntu-base-26.04.1-base-arm64.tar.xz`) | Present |
| XZ support of commons-compress (`org.tukaani:xz`, `commons-io`) | Pass (checked by `tools/dex-audit.py`) |
| No Termux bootstrap inside the APK | Pass (import from the device storage) |
| Telegram delivery (`sendDocument` and `sendMessage`) | Pass |

APK: `UbuntuTerm-arm64-v8a.apk`, 29,269,270 bytes,
SHA-256 `9a262ae6e201e672a6b2e103bb63d3329710bc2364bfa34458f2512b499f19b7`.

The bootstrap that has to be imported on the device is published as the artifact
`bootstrap-aarch64` of the run
<https://github.com/yamenfhamsy-member/UbuntuTerm/actions/runs/43308960630>
(33,221,845 bytes, built for `com.ubutumerm.app`).

## Layer 2 – application tests (require a device or emulator)

These tests need a real Android runtime and are therefore **not executed** by the build
workflow. The list is part of the manual test plan:

1. install the APK on an `arm64-v8a` device,
2. start the application → the installation screen appears,
3. import a bootstrap archive from the file picker and confirm the progress dialog appears and
   the app does not crash,
4. the installation completes and Ubuntu is entered automatically,
5. the terminal shows the Ubuntu prompt (`root@…`) and `cat /etc/os-release` reports
   `26.04.1`,
6. close and reopen the application → Ubuntu is entered again without reinstalling,
7. install the new APK over the old one → the rootfs, the home directory and the installed
   packages are still there,
8. `termux-toast` shows a toast, `termux-battery-status` returns JSON,
   `termux-clipboard-set`/`termux-clipboard-get` round trip the clipboard.
9. open the context menu → "Storage access" → grant the storage permission (on Android 11 and
   above use "All files access", on Android 10 and below use the runtime dialog),
10. restart the session and check inside the guest:
    `ls ~/storage/shared` shows the content of the shared storage,
    `touch ~/storage/shared/ubutumerm-test && ls ~/storage/shared` finds the file on the device,
    `ls ~/storage/downloads ~/storage/dcim` lists the directories of the device,
11. create a real directory at `~/storage/shared-test` and run the repair from the Ubuntu
    manager → the directory still exists (the repair never deletes user data),
12. open the context menu → "Ubuntu manager" → "Repair installation" → the session still works
    and the installed packages are still there,
13. type an Arabic sentence in the terminal (`echo "مرحبا بالعالم"`) → the text is rendered from
    right to left and is not mirrored or cut off,
14. run `termux-torch on` → the system asks for the camera permission and the torch is toggled
    after the grant,
15. run `termux-wake-lock` → the session keeps running with the screen off (or the device stays
    awake), `termux-wake-unlock` releases the lock again,
16. run `termux-open-url https://ubuntu.com` → the browser of the device opens the address.

## Layer 3 – Ubuntu guest tests (executed inside the guest)

| Test | Command | Expected result |
|---|---|---|
| Guest identity | `cat /etc/os-release` | `VERSION="26.04.1 LTS (Resolute Raccoon)"` |
| Architecture | `dpkg --print-architecture` | `arm64` |
| Package source | `grep URIs /etc/apt/sources.list.d/ubuntu.sources` | `http://ports.ubuntu.com/ubuntu-ports/` |
| Package sandbox | `grep Sandbox /etc/apt/apt.conf.d/99ubuntuterm-proot` | `APT::Sandbox::User "root";` |
| Package index | `apt-get update` | no error |
| Installation | `apt-get install -y python3 git curl ca-certificates` | packages installed |
| Consistency | `apt-get check` | no broken package |
| Consistency | `dpkg --audit` | no output |
| Hard links | `ls -l /usr/bin/arch /usr/bin/ls` | symbolic links or shared inodes, no independent copies (the rootfs must not grow by a gigabyte) |
| Python | `python3 -c "print('UbuntuTerm test passed')"` | prints the text |
| Git | `git --version` | prints a version |
| Curl | `curl --version` | prints a version |
| HTTPS/TLS | `curl -sSI https://ports.ubuntu.com` | HTTP status line |
| DNS | `getent hosts ports.ubuntu.com` | an address |
| Compiler | `gcc hello.c -o hello && ./hello` | prints the test text |
| Persistence | create a file, restart the session | the file is still there |

## Layer 4 – Android API tests (executed from inside the guest)

| Test | Command | Expected result |
|---|---|---|
| Toast | `termux-toast "hello"` | toast is shown, exit code 0 |
| Battery | `termux-battery-status` | JSON on stdout |
| Clipboard write | `termux-clipboard-set "hello"` | exit code 0 |
| Clipboard read | `termux-clipboard-get` | prints `hello` |
| Notification | `termux-notification -t t -c c` | notification is shown |
| Wake lock | `termux-wake-lock && termux-wake-unlock` | exit code 0, the notification of the running session stays |
| Browser | `termux-open-url https://ubuntu.com` | the browser opens the address |
| Browser, refused scheme | `termux-open-url file:///etc/passwd` | exit code 0, no application is started |
| Torch | `termux-torch on` / `termux-torch off` | the first run requests the camera permission, then the torch is toggled |
| Wi-Fi | `termux-wifi-connectioninfo` | JSON on stdout |
| Removed methods | `termux-sms-list` | command not found (the wrapper was removed) |
| Protocol | the client returns a non-zero exit code and an error on stderr when the application is not running | error is reported |

The bridge was verified by the static analysis of the protocol (see
[ARCHITECTURE.md](ARCHITECTURE.md)) and by the successful round trip of the client in
the CI environment; the runtime verification on a device is part of the manual test plan.

## How to repeat the tests

```bash
# build and verification (GitHub Actions)
gh workflow run ubutumerm-build.yml --repo yamenfhamsy-member/UbuntuTerm
gh run watch --repo yamenfhamsy-member/UbuntuTerm

# inside the guest, after installing the APK on a device
uname -a
cat /etc/os-release
dpkg --print-architecture
grep URIs /etc/apt/sources.list.d/ubuntu.sources
apt-get update && apt-get install -y python3 git curl ca-certificates
apt-get check && dpkg --audit
python3 -c "print('UbuntuTerm test passed')"
termux-battery-status
termux-toast "UbuntuTerm"
termux-wake-lock && termux-wake-unlock
termux-open-url https://ubuntu.com
```
