#!/usr/bin/env bash
#
# UbuntuTerm pre-flight checks.
#
# The script validates the sources of the repository *before* the (expensive) build is started:
#
#   1. javac type check of every Java source (tools/javac-check.sh)
#   2. shellcheck of every shell script, including the guest side Termux:API wrappers
#   3. actionlint of every GitHub Actions workflow
#   4. yamllint of every YAML file
#
# The tools are downloaded into tools/.cache/bin when they are not installed, so the script works
# on a plain machine without any additional setup.
#
# Usage:
#   tools/preflight.sh [--skip-javac]

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE_DIR="$REPO_ROOT/tools/.cache"
BIN_DIR="$CACHE_DIR/bin"
WORK_DIR="$CACHE_DIR/preflight"
REPORT_DIR="$WORK_DIR/reports"

SKIP_JAVAC=0
[ "${1:-}" = "--skip-javac" ] && SKIP_JAVAC=1

FAILED=0

log() { printf '[preflight] %s\n' "$*"; }
fail() { printf '[preflight] FAILED: %s\n' "$*" >&2; FAILED=1; }

mkdir -p "$BIN_DIR" "$WORK_DIR" "$REPORT_DIR"

# --- shellcheck ---------------------------------------------------------------------------------

ensure_shellcheck() {
    if command -v shellcheck >/dev/null 2>&1; then
        command -v shellcheck
        return 0
    fi
    local version="v0.11.0"
    local target="$BIN_DIR/shellcheck"
    if [ ! -x "$target" ]; then
        log "downloading shellcheck $version" >&2
        local archive="$CACHE_DIR/shellcheck.tar.xz"
        if ! curl -fsSL --retry 3 --max-time 180 -o "$archive" \
            "https://github.com/koalaman/shellcheck/releases/download/${version}/shellcheck-${version}.linux.x86_64.tar.xz"; then
            return 1
        fi
        tar -xJf "$archive" -C "$WORK_DIR" || return 1
        cp "$WORK_DIR/shellcheck-${version}/shellcheck" "$target" && chmod +x "$target"
    fi
    echo "$target"
}

run_shellcheck() {
    local tool
    tool="$(ensure_shellcheck)" || { fail "shellcheck is not available"; return; }
    log "running shellcheck on the shell scripts"
    local scripts=()
    while IFS= read -r script; do scripts+=("$script"); done < <(
        find "$REPO_ROOT/app/src/main/assets/ubuntu/bin" "$REPO_ROOT/tools" -type f -name '*.sh' 2>/dev/null
        find "$REPO_ROOT/app/src/main/assets/ubuntu/bin" -type f ! -name '*.py' ! -name '*.pl' 2>/dev/null
    )
    if [ ${#scripts[@]} -eq 0 ]; then
        log "no shell scripts found"
        return
    fi
    # The guest wrappers are POSIX shell scripts of the Termux:API package.
    # SC2034 (unused variable) is excluded: the wrappers are taken from the upstream
    # Termux:API package and contain variables that are only used by other scripts.
    if ! "$tool" --severity=warning --external-sources --exclude=SC2034 "${scripts[@]}" \
        > "$REPORT_DIR/shellcheck.txt" 2>&1; then
        cat "$REPORT_DIR/shellcheck.txt" >&2
        fail "shellcheck reported findings"
    else
        log "shellcheck: OK"
    fi
}

# --- actionlint ---------------------------------------------------------------------------------

ensure_actionlint() {
    if command -v actionlint >/dev/null 2>&1; then
        command -v actionlint
        return 0
    fi
    local version="1.7.12"
    local target="$BIN_DIR/actionlint"
    if [ ! -x "$target" ]; then
        log "downloading actionlint $version" >&2
        local archive="$CACHE_DIR/actionlint.tar.gz"
        if ! curl -fsSL --retry 3 --max-time 180 -o "$archive" \
            "https://github.com/rhysd/actionlint/releases/download/v${version}/actionlint_${version}_linux_amd64.tar.gz"; then
            return 1
        fi
        tar -xzf "$archive" -C "$WORK_DIR" || return 1
        cp "$WORK_DIR/actionlint" "$target" && chmod +x "$target"
    fi
    echo "$target"
}

run_actionlint() {
    local tool
    tool="$(ensure_actionlint)" || { fail "actionlint is not available"; return; }
    log "running actionlint on the workflows"
    local workflows=()
    while IFS= read -r workflow; do workflows+=("$workflow"); done < <(
        find "$REPO_ROOT/.github/workflows" -type f -name '*.yml' 2>/dev/null
    )
    if [ ${#workflows[@]} -eq 0 ]; then
        log "no workflows found"
        return
    fi
    if ! "$tool" -color=false "${workflows[@]}" > "$REPORT_DIR/actionlint.txt" 2>&1; then
        cat "$REPORT_DIR/actionlint.txt" >&2
        fail "actionlint reported findings"
    else
        log "actionlint: OK"
    fi
}

# --- yamllint -----------------------------------------------------------------------------------

ensure_yamllint() {
    if python3 -m yamllint --version >/dev/null 2>&1; then
        return 0
    fi
    log "installing yamllint" >&2
    python3 -m pip install --user --quiet yamllint >/dev/null 2>&1 || return 1
    python3 -m yamllint --version >/dev/null 2>&1
}

run_yamllint() {
    if ! ensure_yamllint; then
        log "yamllint is not available, skipping"
        return
    fi
    log "running yamllint"
    local files=()
    while IFS= read -r file; do files+=("$file"); done < <(
        find "$REPO_ROOT/.github" "$REPO_ROOT/app" -type f \( -name '*.yml' -o -name '*.yaml' \) 2>/dev/null
    )
    if [ ${#files[@]} -eq 0 ]; then
        log "no YAML files found"
        return
    fi
    if ! python3 -m yamllint -d "{extends: default, rules: {line-length: disable, truthy: disable, comments: disable, document-start: disable, indentation: disable, braces: disable, brackets: disable, empty-lines: disable, quoted-strings: disable}}" \
        "${files[@]}" > "$REPORT_DIR/yamllint.txt" 2>&1; then
        cat "$REPORT_DIR/yamllint.txt" >&2
        fail "yamllint reported findings"
    else
        log "yamllint: OK"
    fi
}

# --- javac type check ---------------------------------------------------------------------------

run_javac() {
    if [ "$SKIP_JAVAC" = "1" ]; then
        log "skipping the javac type check"
        return
    fi
    log "running the javac type check"
    if ! "$REPO_ROOT/tools/javac-check.sh" > "$REPORT_DIR/javac.txt" 2>&1; then
        cat "$REPORT_DIR/javac.txt" >&2
        fail "the javac type check reported errors"
    else
        log "javac type check: OK"
    fi
}

run_shellcheck
run_actionlint
run_yamllint
run_javac

if [ "$FAILED" -ne 0 ]; then
    printf '[preflight] FAILED: at least one check reported findings\n' >&2
    exit 1
fi

printf '[preflight] OK: all checks passed\n'
