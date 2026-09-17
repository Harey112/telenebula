#!/usr/bin/env bash
#
# Builds mobileNebula.aar (gomobile bindings for nebula) and drops it into
# vpn/local-maven/ (a module-local maven repo) where the :vpn Gradle module resolves it.
#
# Requirements on the build machine:
#   - go (>= the version in mobile_nebula's go.mod; GOTOOLCHAIN=auto will
#     fetch a newer toolchain automatically if needed)
#   - Android SDK + NDK, with ANDROID_HOME (and ideally ANDROID_NDK_HOME) set
#   - git, network access
#
# The produced AAR only changes when upstream mobile_nebula changes, so commit
# it (or cache it) — app builds then need no Go toolchain at all.
#
# Env overrides:
#   MOBILE_NEBULA_REF  git ref of DefinedNet/mobile_nebula to build (default: main)
#   NEBULA_AAR_WORKDIR reuse a working directory instead of a fresh mktemp

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MAVEN_DIR="$ROOT/vpn/local-maven/net/defined/mobileNebula/1.0.0"
OUT="$MAVEN_DIR/mobileNebula-1.0.0.aar"
REF="${MOBILE_NEBULA_REF:-main}"
WORK="${NEBULA_AAR_WORKDIR:-$(mktemp -d)}"

command -v go >/dev/null 2>&1 || { echo "ERROR: go not found on PATH"; exit 1; }
command -v git >/dev/null 2>&1 || { echo "ERROR: git not found on PATH"; exit 1; }

if [[ -z "${ANDROID_HOME:-}" && -z "${ANDROID_SDK_ROOT:-}" ]]; then
  echo "ERROR: ANDROID_HOME / ANDROID_SDK_ROOT is not set (Android SDK + NDK required)"
  exit 1
fi

export GOTOOLCHAIN="${GOTOOLCHAIN:-auto}"
GOBIN="$(go env GOPATH)/bin"
export PATH="$GOBIN:$PATH"

echo "==> Fetching DefinedNet/mobile_nebula @ $REF"
if [[ ! -d "$WORK/mobile_nebula" ]]; then
  git clone --depth 1 --branch "$REF" https://github.com/DefinedNet/mobile_nebula "$WORK/mobile_nebula" \
    || { git clone https://github.com/DefinedNet/mobile_nebula "$WORK/mobile_nebula" && git -C "$WORK/mobile_nebula" checkout "$REF"; }
fi

cd "$WORK/mobile_nebula/nebula"

echo "==> Installing gomobile"
# Skip when already installed — keeps reruns working on flaky networks
command -v gomobile >/dev/null 2>&1 || go install golang.org/x/mobile/cmd/gomobile@latest
command -v gobind >/dev/null 2>&1 || go install golang.org/x/mobile/cmd/gobind@latest
gomobile init

echo "==> gomobile bind (this takes a few minutes)"
# Same invocation as upstream's nebula/Makefile
gomobile bind -trimpath -v --target=android -androidapi=26

mkdir -p "$MAVEN_DIR"
cp mobileNebula.aar "$OUT"
cat > "$MAVEN_DIR/mobileNebula-1.0.0.pom" <<'POM'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>net.defined</groupId>
  <artifactId>mobileNebula</artifactId>
  <version>1.0.0</version>
  <packaging>aar</packaging>
</project>
POM
echo "==> Wrote $OUT"
