#!/bin/bash
#
# Gives a hardened runtime to Mach-O binaries buried inside the bundled jars.
#
# Notarization looks *inside* archives. The Compose plugin already signs the native libraries it
# finds in them — libpty, libjnidispatch and skiko all come out hardened — but it matches them by
# extension, and pty4j ships an executable with none at all:
#
#     resources/com/pty4j/native/darwin/pty4j-unix-spawn-helper
#
# It arrives signed by JetBrains with flags=0x0(none), passes `codesign --verify --deep --strict`,
# and is rejected by Apple with "The executable does not have the hardened runtime enabled". This
# is written generally rather than against that one path, so the next dependency to bury a binary
# does not cost another round trip.
#
# Usage: harden-embedded-natives.sh <Forest.app> <signing identity> <entitlements.plist>
set -euo pipefail

app="$1"
identity="$2"
entitlements="$3"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
hardened=0

for jar in "$app"/Contents/app/*.jar; do
    dir="$work/$(basename "$jar" .jar)"
    mkdir -p "$dir"
    # Skipping .class files is most of the speed, and it also sidesteps the fact that a fat Mach-O
    # header and a Java class file share the CAFEBABE magic number.
    unzip -qo "$jar" -d "$dir" -x '*.class' 2>/dev/null || true

    while IFS= read -r -d '' file; do
        case "$(file -b "$file")" in Mach-O*) ;; *) continue ;; esac
        if codesign -dv --verbose=2 "$file" 2>&1 | grep -q 'flags=0x10000(runtime)'; then
            continue
        fi
        entry="${file#"$dir"/}"
        echo "hardening $(basename "$jar")!$entry"
        codesign --force --timestamp --options runtime \
            --entitlements "$entitlements" -s "$identity" "$file"
        (cd "$dir" && zip -q "$jar" "$entry")
        hardened=$((hardened + 1))
    done < <(find "$dir" -type f -print0)
done

if [ "$hardened" -eq 0 ]; then
    echo "every embedded binary already has a hardened runtime"
    exit 0
fi

# The bundle seals its own resources and those jars are resources, so it has to be signed again.
codesign --force --timestamp --options runtime \
    --entitlements "$entitlements" -s "$identity" "$app"
echo "hardened $hardened embedded binaries and re-signed the bundle"
