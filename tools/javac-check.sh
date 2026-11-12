#!/usr/bin/env bash
#
# UbuntuTerm local pre-flight build check.
#
# The script type checks every Java source of the application with javac, using the Android
# platform jar and the Maven artifacts of the build. It does NOT run Gradle and it does NOT
# produce an APK, it only answers the question "would the Gradle build compile?".
#
# Usage:
#   tools/javac-check.sh [--clean]
#
# The downloaded artifacts are cached in tools/.cache so that later runs are fast.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE_DIR="$REPO_ROOT/tools/.cache"
LIB_DIR="$CACHE_DIR/lib"
OUT_DIR="$CACHE_DIR/out"
GEN_DIR="$CACHE_DIR/generated"

ANDROID_JAR_VERSION="36"
ANDROID_PLATFORM_ZIP="platform-${ANDROID_JAR_VERSION}_r01.zip"
ANDROID_PLATFORM_URL="https://dl.google.com/android/repository/${ANDROID_PLATFORM_ZIP}"

GOOGLE_MAVEN="https://dl.google.com/dl/android/maven2"
MAVEN_CENTRAL="https://repo1.maven.org/maven2"
JITPACK="https://jitpack.io"

# group:artifact:version[:jar|aar] - the artifact type is detected automatically.
DEPENDENCIES=(
    "androidx.annotation:annotation:1.9.0"
    "androidx.core:core:1.13.1"
    "androidx.documentfile:documentfile:1.0.1"
    "androidx.media:media:1.7.0"
    "androidx.drawerlayout:drawerlayout:1.2.0"
    "androidx.preference:preference:1.2.1"
    "androidx.viewpager:viewpager:1.0.0"
    "androidx.appcompat:appcompat:1.6.1"
    "androidx.window:window:1.1.0"
    "androidx.recyclerview:recyclerview:1.3.2"
    "androidx.fragment:fragment:1.3.6"
    "androidx.activity:activity:1.6.1"
    "androidx.collection:collection:1.2.0"
    "androidx.savedstate:savedstate:1.2.0"
    "androidx.loader:loader:1.0.0"
    "androidx.customview:customview:1.1.0"
    "androidx.arch.core:core-common:2.1.0"
    "androidx.arch.core:core-runtime:2.1.0"
    "androidx.interpolator:interpolator:1.0.0"
    "androidx.versionedparcelable:versionedparcelable:1.1.0"
    "androidx.lifecycle:lifecycle-common:2.5.1"
    "androidx.lifecycle:lifecycle-runtime:2.5.1"
    "androidx.lifecycle:lifecycle-viewmodel:2.5.1"
    "androidx.lifecycle:lifecycle-livedata-core:2.5.1"
    "androidx.emoji2:emoji2:1.3.0"
    "androidx.emoji2:emoji2-views-helper:1.3.0"
    "androidx.resourceinspection:resourceinspection-annotation:1.0.1"
    "androidx.tracing:tracing:1.0.0"
    "androidx.concurrent:concurrent-futures:1.0.0"
    "androidx.dynamicanimation:dynamicanimation:1.0.0"
    "androidx.transition:transition:1.4.1"
    "androidx.annotation:annotation-jvm:1.9.0"
    "com.google.android.material:material:1.12.0"
    "com.google.guava:guava:24.1-jre"
    "com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava"
    "io.noties.markwon:core:4.6.2"
    "io.noties.markwon:ext-strikethrough:4.6.2"
    "io.noties.markwon:linkify:4.6.2"
    "io.noties.markwon:recycler:4.6.2"
    "org.lsposed.hiddenapibypass:hiddenapibypass:6.1"
    "commons-io:commons-io:2.5"
    "org.apache.commons:commons-compress:1.27.1"
    "com.termux:termux-am-library:v2.0.0"
    "org.commonmark:commonmark:0.21.0"
    "org.commonmark:commonmark-ext-gfm-strikethrough:0.21.0"
    "org.commonmark:commonmark-ext-gfm-tables:0.21.0"
    "org.commonmark:commonmark-ext-heading-anchor:0.21.0"
    "org.jetbrains:annotations:24.1.0"
    "com.google.code.findbugs:jsr305:3.0.2"
)

log() { printf '[javac-check] %s\n' "$*"; }

fail() {
    printf '[javac-check] FAILED: %s\n' "$*" >&2
    exit 1
}

fetch() {
    # fetch <url> <target>
    local url="$1" target="$2"
    [ -s "$target" ] && return 0
    log "downloading $(basename "$target")"
    curl -fsSL --retry 3 --retry-delay 2 --max-time 180 -o "$target.part" "$url" 2>/dev/null \
        || { rm -f "$target.part"; return 1; }
    mv "$target.part" "$target"
}

prepare_android_jar() {
    local zip="$CACHE_DIR/$ANDROID_PLATFORM_ZIP"
    local jar="$LIB_DIR/android.jar"
    [ -s "$jar" ] && return 0
    mkdir -p "$CACHE_DIR" "$LIB_DIR"
    fetch "$ANDROID_PLATFORM_URL" "$zip" || fail "cannot download $ANDROID_PLATFORM_URL"
    log "extracting android.jar"
    unzip -o -j "$zip" "*/android.jar" -d "$LIB_DIR" >/dev/null || fail "cannot extract android.jar"
    [ -s "$jar" ] || fail "android.jar missing"
}

prepare_dependencies() {
    mkdir -p "$LIB_DIR"
    local dep group artifact version path url jar
    for dep in "${DEPENDENCIES[@]}"; do
        group="${dep%%:*}"
        rest="${dep#*:}"
        artifact="${rest%%:*}"
        version="${rest#*:}"
        group_path="${group//.//}"
        jar="$LIB_DIR/${group}_${artifact}_${version}.jar"
        [ -s "$jar" ] && continue

        # Detect the packaging type from the Maven metadata.
        local found=""
        for ext in jar aar; do
            url="$GOOGLE_MAVEN/$group_path/$artifact/$version/$artifact-$version.$ext"
            if fetch "$url" "$CACHE_DIR/${group}_${artifact}_${version}.$ext"; then
                found="$ext"
                break
            fi
            url="$MAVEN_CENTRAL/$group_path/$artifact/$version/$artifact-$version.$ext"
            if fetch "$url" "$CACHE_DIR/${group}_${artifact}_${version}.$ext"; then
                found="$ext"
                break
            fi
            url="$JITPACK/$group_path/$artifact/$version/$artifact-$version.$ext"
            if fetch "$url" "$CACHE_DIR/${group}_${artifact}_${version}.$ext"; then
                found="$ext"
                break
            fi
        done

        if [ -z "$found" ]; then
            log "WARN: artifact $dep not found, skipping"
            continue
        fi

        local archive="$CACHE_DIR/${group}_${artifact}_${version}.$found"
        if [ "$found" = "aar" ]; then
            log "extracting classes.jar from ${group}:${artifact}:${version}"
            unzip -o -j "$archive" "classes.jar" -d "$CACHE_DIR" >/dev/null \
                || { log "WARN: cannot extract $archive"; continue; }
            mv "$CACHE_DIR/classes.jar" "$jar" || continue
        else
            cp "$archive" "$jar"
        fi
    done
}

collect_sources() {
    find "$REPO_ROOT/app/src/main/java" \
         "$REPO_ROOT/termux-shared/src/main/java" \
         "$REPO_ROOT/terminal-view/src/main/java" \
         "$REPO_ROOT/terminal-emulator/src/main/java" \
         -name '*.java' 2>/dev/null | sort
}

main() {
    [ "${1:-}" = "--clean" ] && rm -rf "$CACHE_DIR"

    mkdir -p "$CACHE_DIR" "$LIB_DIR" "$OUT_DIR" "$GEN_DIR"

    log "preparing the Android platform jar (API $ANDROID_JAR_VERSION)"
    prepare_android_jar || fail "the Android platform jar is not available"

    log "preparing the Maven dependencies"
    prepare_dependencies

    log "generating the R and BuildConfig stubs"
    rm -rf "$GEN_DIR"
    python3 "$REPO_ROOT/tools/gen_r_stub.py" "$REPO_ROOT" "$GEN_DIR" "com.ubutumerm.app" \
        || fail "cannot generate the resource stubs"

    local cp
    cp="$LIB_DIR/android.jar:"
    cp="$cp$(find "$LIB_DIR" -name '*.jar' ! -name 'android.jar' | tr '\n' ':')"
    cp="$cp$GEN_DIR"

    local sources
    sources="$(collect_sources | tr '\n' ' ')"

    log "compiling $(echo "$sources" | wc -w) source files"
    rm -rf "$OUT_DIR" && mkdir -p "$OUT_DIR"

    local log_file="$CACHE_DIR/javac.log"
    javac -nowarn -proc:none -encoding UTF-8 --release 11 \
        -classpath "$cp" \
        -d "$OUT_DIR" \
        $sources > "$log_file" 2>&1
    local rc=$?

    if [ $rc -ne 0 ]; then
        log "javac reported errors, see below:"
        cat "$log_file" >&2
        exit 1
    fi

    log "checking the build configuration"
    local gradle="$REPO_ROOT/app/build.gradle"
    local constants="$REPO_ROOT/termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java"
    local manifest="$REPO_ROOT/app/src/main/AndroidManifest.xml"
    grep -q 'applicationId "com.ubutumerm.app"' "$gradle" \
        || fail "the application id is not com.ubutumerm.app"
    grep -q 'namespace "com.termux"' "$gradle" \
        || fail "the namespace changed unexpectedly"
    grep -q 'abiFilters "arm64-v8a"' "$gradle" \
        || fail "the ABI filter is not arm64-v8a"
    grep -q 'versionName "1.0.0"' "$gradle" \
        || fail "the version name changed unexpectedly"
    grep -q 'outputFileName = new File("UbuntuTerm-arm64-v8a.apk")' "$gradle" \
        || fail "the APK file name changed unexpectedly"
    grep -q 'TERMUX_PACKAGE_NAME = "com.ubutumerm.app"' "$constants" \
        || fail "TERMUX_PACKAGE_NAME is not com.ubutumerm.app"
    grep -q 'sharedUserId' "$manifest" \
        && fail "the manifest still declares a sharedUserId"
    grep -q 'com.ubutumerm.app.api://listen' "$REPO_ROOT/app/src/main/assets/ubuntu/termux-api.pl" \
        || fail "the API client does not use the UbuntuTerm socket address"

    log "checking the guest side scripts"
    local script_error=0
    for script in "$REPO_ROOT"/app/src/main/assets/ubuntu/bin/*; do
        bash -n "$script" || script_error=1
    done
    perl -c "$REPO_ROOT/app/src/main/assets/ubuntu/termux-api.pl" >/dev/null || script_error=1
    python3 -m py_compile "$REPO_ROOT/app/src/main/assets/ubuntu/termux-api.py" || script_error=1
    rm -rf "$REPO_ROOT/app/src/main/assets/ubuntu/__pycache__"
    [ $script_error -eq 0 ] || fail "the guest side scripts have syntax errors"

    log "OK: all sources compile and the guest scripts are valid, the Gradle build would reach the packaging step"
    exit 0
}

main "$@"
