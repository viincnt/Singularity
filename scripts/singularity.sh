#!/usr/bin/env bash
# Internal dev CLI (macOS/Linux): vendors third_party/ native SDKs and
# launches the right runClient for this OS. Not shipped to players.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

VULKAN_HEADERS_URL="https://github.com/KhronosGroup/Vulkan-Headers.git"
DLSS_URL="https://github.com/NVIDIA/DLSS.git"

is_populated() {
    [ -d "$1" ] && [ -n "$(ls -A "$1" 2>/dev/null)" ]
}

vendor_clone() {
    local url="$1" dest="$2" force="$3"
    if is_populated "$dest" && [ "$force" != "1" ]; then
        echo "[vendor] $dest already populated, skipping (pass --force to re-clone)"
        return
    fi
    rm -rf "$dest"
    echo "[vendor] cloning $url -> $dest"
    git clone --depth 1 "$url" "$dest"
    # Strip the inner .git so this is a plain vendored tree, not a nested
    # repo/gitlink - third_party/dlss and third_party/vulkan-headers are
    # gitignored and managed by this script, not by git submodules.
    rm -rf "$dest/.git"
}

cmd_vendor() {
    local force=0
    [ "${1:-}" = "--force" ] && force=1
    vendor_clone "$VULKAN_HEADERS_URL" "third_party/vulkan-headers" "$force"
    vendor_clone "$DLSS_URL" "third_party/dlss" "$force"
    echo "[vendor] done"
}

current_os() {
    case "$(uname -s)" in
        Darwin) echo "macos" ;;
        Linux) echo "linux" ;;
        *) echo "unknown" ;;
    esac
}

ensure_vendored_for() {
    local os_name="$1" missing=()
    is_populated "third_party/vulkan-headers" || missing+=("third_party/vulkan-headers")
    if [ "$os_name" = "windows" ] && ! is_populated "third_party/dlss"; then
        missing+=("third_party/dlss")
    fi
    if [ "${#missing[@]}" -gt 0 ]; then
        echo "[run] missing: ${missing[*]}"
        echo "[run] run ./scripts/singularity.sh vendor first"
        return 1
    fi
    return 0
}

ensure_java_home() {
    # Homebrew's openjdk formulae don't register themselves with macOS's
    # java_home mechanism, so a freshly `brew install`'d JDK is often
    # invisible to /usr/bin/java (and therefore ./gradlew) until JAVA_HOME is
    # set explicitly. Look in the usual spots before giving up.
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        return
    fi
    local candidate candidates=(
        "$(/usr/libexec/java_home -v 25 2>/dev/null || true)"
        "/opt/homebrew/opt/openjdk@25"
        "/usr/local/opt/openjdk@25"
        "/opt/homebrew/opt/openjdk"
        "/usr/local/opt/openjdk"
    )
    for candidate in "${candidates[@]}"; do
        if [ -n "$candidate" ] && [ -x "$candidate/bin/java" ]; then
            export JAVA_HOME="$candidate"
            export PATH="$JAVA_HOME/bin:$PATH"
            echo "[run] JAVA_HOME not set/found by macOS; using Homebrew JDK at $JAVA_HOME"
            return
        fi
    done
}

cmd_run() {
    local loader="neoforge"
    while [ $# -gt 0 ]; do
        case "$1" in
            --loader) loader="$2"; shift 2 ;;
            *) echo "unknown argument: $1" >&2; exit 1 ;;
        esac
    done

    local os_name
    os_name="$(current_os)"
    [ "$os_name" = "macos" ] && ensure_java_home
    if [ "$os_name" = "linux" ]; then
        echo "[run] no native bridge (DLSS or MetalFX) targets Linux yet; runClient will start without one."
    elif [ "$os_name" = "unknown" ]; then
        echo "[run] unrecognized OS from uname -s; use singularity.bat on Windows." >&2
        exit 1
    else
        ensure_vendored_for "$os_name" || exit 1
    fi

    echo "[run] detected $os_name, launching $loader: ./gradlew :$loader:runClient"
    exec ./gradlew ":$loader:runClient"
}

case "${1:-}" in
    vendor) shift; cmd_vendor "$@" ;;
    run) shift; cmd_run "$@" ;;
    *)
        echo "usage: $0 {vendor [--force] | run [--loader neoforge|fabric]}" >&2
        exit 1
        ;;
esac
