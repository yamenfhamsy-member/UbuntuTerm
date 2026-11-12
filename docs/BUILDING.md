# Building UbuntuTerm

> **The APK is built by GitHub Actions only.** No local Gradle/Android build is part of the
> workflow, this keeps the development machine small and guarantees that every published APK
> comes from the same, reproducible pipeline.

## Workflow

`.github/workflows/ubutumerm-build.yml`

| Job | Purpose |
|---|---|
| `build` | compile the bootstrap, prepare the Ubuntu asset, build the APK, verify it, upload the artifact |
| `notify` | deliver the APK and the status message to Telegram (only for trusted events) |

Triggers: `push` to `master`, `push` of a `v*` tag, `pull_request` (build only, no
notification), `workflow_dispatch`.

## Steps of the `build` job

1. **Checkout** the repository.
2. **Prepare `termux-packages`** at the pinned commit
   `b189a79f3b9606b108b45460a4c50756d91e2b9d` and apply
   `patches/termux-packages-ubutantterm.patch`.
3. **Compile the bootstrap** (`aarch64` only) inside the official
   `ghcr.io/termux/package-builder` container. This produces a bootstrap whose binaries are
   built for `/data/data/com.ubutumerm.app/files/usr`.
4. **Audit the bootstrap**: required files present, no reference to `/data/data/com.termux`
   in any ELF binary, shebang, script or configuration, `proot` points to the UbuntuTerm
   prefix.
5. **Strip the bootstrap**: `include/`, `share/doc`, `share/man`, `share/info` and
   `share/misc` are removed (they are development/documentation files) and the archive is
   repacked. This keeps the APK small enough for the Telegram file upload.
6. **Prepare the Ubuntu asset**: the official `ubuntu-base-26.04.1-base-arm64.tar.gz` is
   downloaded, checked against the official SHA-256, converted to an xz compressed tar and
   stored in `app/src/main/assets/ubuntu/` together with `rootfs-manifest.properties`.
7. **Build the APK**: `./gradlew --no-daemon testReleaseUnitTest assembleRelease` with
   `TERMUX_SPLIT_APKS_FOR_RELEASE_BUILDS=1` and `UBUNTUTERM_BOOTSTRAP_LOCAL_ONLY=1`.
8. **Verify the APK**:
   * `aapt2 dump badging` → package id `com.ubutumerm.app`, label `UbuntuTerm`,
   * `native-code` contains `arm64-v8a` only,
   * `unzip -l` → no native library outside of `lib/arm64-v8a/`,
   * `apksigner verify --print-certs` → signature valid,
   * the bundled Ubuntu asset is present in the APK.
9. **Upload the artifact** `UbuntuTerm-arm64-v8a` containing
   `apk/UbuntuTerm-arm64-v8a.apk`, `SHA256SUMS.txt` and the verification reports.

## Pre-flight checks (before every build)

The pipeline validates the repository **before** the expensive build starts. The same checks can
be run locally:

```bash
tools/preflight.sh              # everything
tools/preflight.sh --skip-javac # without the type check
```

| Tool | What it checks | Notes |
|---|---|---|
| `shellcheck` | the guest side Termux:API wrappers and the helper scripts | the wrappers are executed with bash |
| `actionlint` | the GitHub Actions workflows, including the shell code of the `run:` blocks | |
| `yamllint` | the YAML files of the repository | |
| `javac` | every Java source of the application | `tools/javac-check.sh` downloads the Android platform jar and the Maven artifacts of the build and type checks the sources without running Gradle and without producing an APK |
| Android Lint | the Android specific problems of the resources and the manifest | runs as part of the Gradle build |

The downloaded artifacts are cached in `tools/.cache`, so only the first run needs the network.

## Running the workflow manually

```bash
gh workflow run ubutumerm-build.yml --repo yamenfhamsy-member/UbuntuTerm
gh run list --repo yamenfhamsy-member/UbuntuTerm
```

The workflow also accepts a `bootstrap_run_id` input: a previously produced
`bootstrap-aarch64` artifact is then reused instead of compiling the bootstrap again, which
saves roughly 35 minutes.

## Release signing

The release build type is signed with a release key when the following repository secrets
exist:

| Secret | Content |
|---|---|
| `UBUNTUTERM_KEYSTORE_BASE64` | base64 encoded keystore |
| `UBUNTUTERM_KEYSTORE_PASSWORD` | keystore password |
| `UBUNTUTERM_KEY_ALIAS` | key alias |
| `UBUNTUTERM_KEY_PASSWORD` | key password |

Without them the APK is a **test build**: it is signed with the public test key
(`app/testkey_untrusted.jks`) that is part of the Termux sources. Such an APK cannot update
an installation that was signed with a different key. The workflow prints the signing mode
(`release` or `test`) into the build log and into the Telegram caption.

## Telegram delivery

The `notify` job runs with `if: always()` so that a failed build also produces a message.
It is restricted to trusted events (`push`, `workflow_dispatch`); pull requests from forks
never see the Telegram secrets.

* the APK is uploaded with `sendDocument` when it is not larger than 50 MB (the limit of the
  Telegram Bot API),
* a second, independent `sendMessage` reports the version, commit, size, SHA-256, the test
  result and the link to the workflow run,
* when the build fails, a failure message with the link to the run is sent instead.

## Repository secrets

```bash
printf '%s' "$TELEGRAM_BOT_TOKEN" | gh secret set TELEGRAM_BOT_TOKEN --repo yamenfhamsy-member/UbuntuTerm
printf '%s' "$TELEGRAM_CHAT_ID"    | gh secret set TELEGRAM_CHAT_ID    --repo yamenfhamsy-member/UbuntuTerm
gh secret list --repo yamenfhamsy-member/UbuntuTerm
```

The local GitHub token is never uploaded to the repository and never used inside the
workflow; the workflow uses the automatic `GITHUB_TOKEN` with `contents: read`.
