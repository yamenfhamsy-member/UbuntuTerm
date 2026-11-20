# UbuntuTerm — agent notes

UbuntuTerm is a fork of termux/termux-app that ships Ubuntu 26.04.1 (arm64) as its default guest.
It is a **multi-module Gradle project**: `:app`, `:termux-shared`, `:terminal-emulator`, `:terminal-view`.

## Build — CI only (no Android SDK locally)

There is no Gradle/Android SDK on the development machine. The APK is produced exclusively by
GitHub Actions (`.github/workflows/ubutumerm-build.yml`, job `build` + `notify`). A push to
`master`, a `v*` tag, a PR, or `workflow_dispatch` triggers it. Run manually:

```bash
gh workflow run ubutumerm-build.yml --repo yamenfhamsy-member/UbuntuTerm --ref <branch>
gh run list --repo yamenfhamsy-member/UbuntuTerm
```

The Gradle command the workflow runs is:

```bash
./gradlew --no-daemon testReleaseUnitTest assembleRelease
```

with `TERMUX_PACKAGE_VARIANT=apt-android-7` and `UBUNTUTERM_BOOTSTRAP_LOCAL_ONLY=1`. Output:
`app/build/outputs/apk/release/UbuntuTerm-arm64-v8a.apk`.

## Local verification (run before every push)

These are the pre-flight checks, identical to CI:

```bash
tools/preflight.sh            # shellcheck + actionlint + yamllint + javac type check
tools/javac-check.sh          # type check every Java source (~220 files, Android API 36)
```

`javac-check.sh` downloads the Android platform jar and Maven deps into `tools/.cache` and
compiles with `javac --release 11`. It is the practical substitute for `./gradlew compile`
because Gradle cannot run without the Android SDK. Re-run after touching Java, shell, or
workflow files.

The pinned actions used by the workflows are listed in `tools/preflight.sh` (`PINNED_ACTIONS`);
adding an action not in that list breaks pre-flight.

## Key constants (verify before changing)

- `app/build.gradle`: `applicationId "com.ubutumerm.app"`, namespace `com.termux`,
  `abiFilters "arm64-v8a"`, `versionName "1.0.0"`, output `UbuntuTerm-arm64-v8a.apk`.
- `gradle.properties`: `minSdkVersion=26`, `targetSdkVersion=28`, `compileSdkVersion=36`.
- `termux-shared/.../TermuxConstants.java`: `TERMUX_PACKAGE_NAME = "com.ubutumerm.app"`.
- `AndroidManifest.xml`: must not declare `sharedUserId`.

`tools/javac-check.sh` asserts most of these at the end — do not edit them out of sync.

## Gotchas

- **Git identity is not configured.** Commit with:
  `git -c user.name="Yamen" -c user.email="yamen@users.noreply.github.com" commit …`
  Never run `git config` to set a permanent identity.
- **Lint rule `PermissionImpliesUnsupportedChromeOsHardware`:** adding `CAMERA` requires
  `<uses-feature android:name="android.hardware.camera" android:required="false" />` in the
  manifest. Lint baseline lives at `app/lint-baseline.xml`; regenerate only with the
  `update_lint_baseline` workflow input.
- **The APK does not embed a Termux bootstrap.** A placeholder is shipped; the user imports
  `bootstrap-aarch64.zip` (built by CI for `com.ubutumerm.app`) from device storage. Do not
  embed the upstream `/data/data/com.termux` bootstrap — it is a different package id and the
  app will crash at startup.
- **`/tmp` gets wiped on this machine.** `tools/.cache` (jars, shellcheck, actionlint) is
  destroyed too. Recover by re-running `tools/preflight.sh`, which re-downloads everything.
- **Unit tests cannot run locally** (no Android SDK/Robolectric assets). They run in CI as
  `testReleaseUnitTest` (JUnit 4 + Robolectric 4.10, `returnDefaultValues=true`).
- **ProGuard/minify is disabled** for release (`minifyEnabled false`) because Termux:API uses
  reflective framework classes.

## Repository layout

- `app/` — Android app (Java + native cpp). Ubuntu guest runtime under
  `app/src/main/java/com/termux/app/ubuntu/`, embedded Termux:API under
  `app/src/main/java/com/termux/api/`, guest wrappers under `app/src/main/assets/ubuntu/`.
- `termux-shared/` — shared library: `TermuxConstants`, shell environment, properties.
- `terminal-emulator/`, `terminal-view/` — unchanged upstream Termux terminal code.
- `patches/` — termux-packages patch for the custom bootstrap.
- `docs/` — ARCHITECTURE.md, TECHNICAL_DECISIONS.md, BUILDING.md, TESTING.md, LIMITATIONS.md,
  REVIEW.md, THIRD_PARTY_NOTICES.md.

## Branch / commit convention

- Work happens on `feature/rtl-arabic` (tracked upstream of this fork). Push with `gh` as
  `yamenfhamsy-member`.
- One logical change per commit. Match existing commit message style:
  `app: add community-requested terminal UX improvements`.
- Do not commit secrets (API keys, keystore passwords). Release signing uses repo secrets
  (`UBUNTUTERM_KEYSTORE_BASE64`, …); without them the APK is a test build signed with the
  public test key and cannot upgrade a differently-signed install.
