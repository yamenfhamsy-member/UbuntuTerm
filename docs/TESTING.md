# Testing

The tests are split into four layers. The result of every layer is recorded in the build
report (`final-output/reports/`) and in the Telegram status message.

## Layer 1 – build tests (executed by GitHub Actions)

| Test | Tool | Expected result |
|---|---|---|
| Gradle build | `./gradlew assembleRelease` | APK is produced |
| Unit tests | `./gradlew testReleaseUnitTest` | all tests pass (includes the tar extractor security tests) |
| Package id | `aapt2 dump badging` | `name='com.ubutumerm.app'` |
| Application label | `aapt2 dump badging` | `application-label:'UbuntuTerm'` |
| Version | `aapt2 dump badging` | `versionCode`/`versionName` are printed into the report |
| ABI | `aapt2 dump badging` + `unzip -l` | only `arm64-v8a`, no `armeabi-v7a`, `x86` or `x86_64` |
| Native libraries | `unzip -l \| grep lib/` | every entry is below `lib/arm64-v8a/` |
| Signature | `apksigner verify --print-certs` | signature is valid |
| Bootstrap isolation | `readelf`/`strings` sweep | no `/data/data/com.termux` reference in the bootstrap |
| Ubuntu asset | `sha256sum` | the archive matches the official Ubuntu checksum |

## Result of the last build

Workflow run: <https://github.com/yamenfhamsy-member/UbuntuTerm/actions/runs/43319721828>

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
| No Termux bootstrap inside the APK | Pass (import from the device storage) |
| Telegram delivery (`sendDocument` and `sendMessage`) | Pass |

APK: `UbuntuTerm-arm64-v8a.apk`, 28,108,434 bytes,
SHA-256 `e46a72da34e87508ae479ee59d63663918a1ba991b01b4c2b31939a6fbbdc299`.

The bootstrap that has to be imported on the device is published as the artifact
`bootstrap-aarch64` of the run
<https://github.com/yamenfhamsy-member/UbuntuTerm/actions/runs/43308960630>
(33,221,845 bytes, built for `com.ubutumerm.app`).

## Layer 2 – application tests (require a device or emulator)

These tests need a real Android runtime and are therefore **not executed** by the build
workflow. The list is part of the manual test plan:

1. install the APK on an `arm64-v8a` device,
2. start the application → the installation screen appears,
3. the installation completes and Ubuntu is entered automatically,
4. the terminal shows the Ubuntu prompt (`root@…`) and `cat /etc/os-release` reports
   `26.04.1`,
5. close and reopen the application → Ubuntu is entered again without reinstalling,
6. install the new APK over the old one → the rootfs, the home directory and the installed
   packages are still there,
7. `termux-toast` shows a toast, `termux-battery-status` returns JSON,
   `termux-clipboard-set`/`termux-clipboard-get` round trip the clipboard.

## Layer 3 – Ubuntu guest tests (executed inside the guest)

| Test | Command | Expected result |
|---|---|---|
| Guest identity | `cat /etc/os-release` | `VERSION="26.04.1 LTS (Resolute Raccoon)"` |
| Architecture | `dpkg --print-architecture` | `arm64` |
| Package index | `apt-get update` | no error |
| Installation | `apt-get install -y python3 git curl ca-certificates` | packages installed |
| Consistency | `apt-get check` | no broken package |
| Consistency | `dpkg --audit` | no output |
| Python | `python3 -c "print('UbuntuTerm test passed')"` | prints the text |
| Git | `git --version` | prints a version |
| Curl | `curl --version` | prints a version |
| HTTPS/TLS | `curl -sSI https://archive.ubuntu.com` | HTTP status line |
| DNS | `getent hosts archive.ubuntu.com` | an address |
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
| Protocol | the client returns a non-zero exit code and an error on stderr when the application is not running | error is reported |

The bridge was verified by the static analysis of the protocol (see
[ARCHITECTURE.md](ARCHITECTURE.md)) and by the successful round trip of the Python client in
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
apt-get update && apt-get install -y python3 git curl ca-certificates
apt-get check && dpkg --audit
python3 -c "print('UbuntuTerm test passed')"
termux-battery-status
termux-toast "UbuntuTerm"
```
