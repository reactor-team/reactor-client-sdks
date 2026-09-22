#!/usr/bin/env bash
#
# Install the Android SDK components pinned in sdks/android/sdk-packages.txt, then verify that
# what is installed is what that file pins.
#
# Why this script exists at all: mise pins the Android *command-line tools* (root mise.toml,
# `android-sdk`), and that is as far as mise can reach — the plugin installs cmdline-tools and
# nothing else. The platform, the build-tools, adb and the NDK are fetched by the `android` CLI
# those tools provide, which mise has no view of and cannot put in mise.lock. sdk-packages.txt is
# their single source of truth and this script is what makes that pin checked rather than merely
# written down.
#
# The verification below is load-bearing rather than belt-and-braces: `android sdk install` exits
# 0 when it silently ignores a spec it cannot resolve ("No url for <name>. Ignoring."), so a
# typo'd or withdrawn pin installs nothing and reports success. Checking afterwards is the only
# thing between that and a build running on whatever the SDK root happened to already contain.
#
# The SDK root is mise's own install directory, which the android-sdk plugin exports as
# ANDROID_HOME. That is deliberate: components land beside the tools that fetched them,
# version-scoped by the cmdline-tools pin, and nowhere near whatever Android SDK the machine
# already has. A shared system SDK is how an unpinned NDK gets used without anyone noticing.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PACKAGE_FILES=("$REPO_ROOT/sdks/android/sdk-packages.txt")

# --emulator adds the instrumented-test set: an emulator and a system image, which are well over
# a gigabyte together and which the ordinary build has no use for.
if [ "${1:-}" = "--emulator" ]; then
  PACKAGE_FILES+=("$REPO_ROOT/sdks/android/sdk-packages-emulator.txt")
elif [ -n "${1:-}" ]; then
  echo "usage: $0 [--emulator]" >&2
  exit 2
fi

for f in "${PACKAGE_FILES[@]}"; do
  if [ ! -f "$f" ]; then
    echo "error: $f not found" >&2
    exit 1
  fi
done

if [ -z "${ANDROID_HOME:-}" ]; then
  cat >&2 <<'MSG'
error: ANDROID_HOME is unset.

The android-sdk tool in the repository-root mise.toml exports it. Run this through mise:

    mise run setup:android

rather than calling the script directly from a shell mise has not activated.
MSG
  exit 1
fi

if ! command -v android >/dev/null 2>&1; then
  echo "error: the 'android' CLI is not on PATH (expected from the mise android-sdk tool)" >&2
  exit 1
fi

# Read the specs here rather than handing the file to the CLI: it has no comment syntax, and the
# comments in sdk-packages.txt are the part that tells the next reader why each version is what
# it is.
PACKAGES=()
for f in "${PACKAGE_FILES[@]}"; do
  while IFS= read -r line; do
    line="${line%%#*}"
    line="$(printf '%s' "$line" | tr -d '[:space:]')"
    [ -n "$line" ] && PACKAGES+=("$line")
  done < "$f"
done

if [ ${#PACKAGES[@]} -eq 0 ]; then
  echo "error: no packages pinned in ${PACKAGE_FILES[*]}" >&2
  exit 1
fi

# Refuse an unversioned spec before installing anything. `android sdk install platform-tools`
# happily installs whatever is newest today, which is exactly what pinning is for.
for pkg in "${PACKAGES[@]}"; do
  case "$pkg" in
    */*|*@*) ;;
    *)
      echo "error: '$pkg' pins no version." >&2
      echo "       Use a versioned path (build-tools/36.1.0) or name@version (platform-tools@37.0.1)." >&2
      exit 1
      ;;
  esac
done

echo "==> Android SDK root: $ANDROID_HOME"
echo "==> Pinned packages:"
printf '      %s\n' "${PACKAGES[@]}"

echo "==> Installing"
for pkg in "${PACKAGES[@]}"; do
  android sdk install --no-metrics "$pkg"
done

# ── Verification ──────────────────────────────────────────────────────────────
#
# `android sdk list` prints an "Installed packages:" block, then an "Available packages:" one.
# Take only the first, as `<path> <version>` pairs.
INSTALLED="$(android sdk list --no-metrics 2>/dev/null \
  | awk '/^Installed packages:/{on=1; next} /^Available packages:/{on=0} on && NF>=2 {print $1, $2}')"

if [ -z "$INSTALLED" ]; then
  echo "error: could not read the installed package list from 'android sdk list'" >&2
  exit 1
fi

status=0

for pkg in "${PACKAGES[@]}"; do
  case "$pkg" in
    *@*)
      # name@version — the path carries no version, so check the version column.
      name="${pkg%@*}"
      want="${pkg#*@}"
      have="$(printf '%s\n' "$INSTALLED" | awk -v n="$name" '$1==n {print $2; exit}')"
      if [ -z "$have" ]; then
        echo "error: pinned package not installed: $pkg" >&2
        status=1
      elif [ "$have" != "$want" ]; then
        echo "error: $name is $have, but sdk-packages.txt pins $want" >&2
        status=1
      fi
      ;;
    *)
      # A versioned path — its presence is the whole check.
      if ! printf '%s\n' "$INSTALLED" | awk '{print $1}' | grep -Fxq "$pkg"; then
        echo "error: pinned package not installed: $pkg" >&2
        status=1
      fi
      ;;
  esac
done

# A *second* version of something pinned — two NDKs, two platforms — is the failure that actually
# bites, because the build picks one and nothing says which. Packages the CLI pulled in on its own
# are deliberately not checked: they are transitive dependencies rather than choices this
# repository is making, and failing on them would mean re-pinning Google's dependency graph.
for pkg in "${PACKAGES[@]}"; do
  case "$pkg" in
    *@*) continue ;;
  esac
  kind="${pkg%/*}"
  extra="$(printf '%s\n' "$INSTALLED" | awk '{print $1}' | grep -E "^${kind}/" | grep -Fxv "$pkg" || true)"
  if [ -n "$extra" ]; then
    echo "error: more than one '${kind}' installed — sdk-packages.txt pins ${pkg}, but the SDK root also has:" >&2
    printf '         %s\n' "$extra" >&2
    echo "       remove it with: android sdk remove <package>" >&2
    status=1
  fi
done

if [ "$status" -ne 0 ]; then
  echo >&2
  echo "The Android SDK does not match sdks/android/sdk-packages.txt." >&2
  exit 1
fi

echo "==> OK: ${#PACKAGES[@]} pinned package(s) installed, no competing versions"
