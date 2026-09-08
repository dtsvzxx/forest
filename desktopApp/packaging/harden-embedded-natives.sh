#!/bin/bash
#
# Makes the app image notarizable: two repairs, and one re-signing that covers both.
#
# They are unrelated faults with the same remedy — the bundle's seals have to be rewritten
# afterwards, innermost first — so doing them apart would mean signing the thing twice.
#
# ---------------------------------------------------------------------------- 1. buried Mach-O
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
# ------------------------------------------------------------------- 2. jlink's licence symlinks
#
# jlink deduplicates the runtime's licence files: `legal/java.desktop/LICENSE` and twenty-six like
# it are symlinks to `../java.base/LICENSE`. jpackage's DMG step copies the app image by *following*
# them, so what lands in the image is twenty-seven regular files where the runtime's seal recorded
# twenty-seven links — and `codesign --verify --deep --strict` on the copy inside the image answers
# "file modified" for every one. Apple reports it as "The signature of the binary is invalid" against
# `Contents/MacOS/Forest` and `runtime/Contents/MacOS/libjli.dylib`, which names the bundles whose
# seals broke rather than the files that broke them, and that is a long way from the cause.
#
# So the links are resolved here, before anything is signed, and the runtime then seals real files.
# jpackage's flattening becomes a copy of what is already there. They are licence texts, about
# 500 KB in total once duplicated, and dereferencing them changes nothing about what the app ships.
#
# Verified rather than assumed: `cp -R` and `hdiutil create -srcfolder` both keep the links, and
# jpackage loses them on Azul 25 and on the other JDK 25s on this machine, so it is jpackage's copy
# and not the image format.
#
# Usage: harden-embedded-natives.sh <Forest.app> <signing identity> <entitlements.plist>
set -euo pipefail

app="$1"
identity="$2"
entitlements="$3"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
hardened=0
flattened=0

while IFS= read -r -d '' link; do
    plain="$work/flattened"
    # `cp` reads *through* a symlink, so this is the target's content, written beside it and then
    # put in its place. Every one of them is a licence file; there is nothing structural here.
    cp "$link" "$plain"
    rm "$link"
    mv "$plain" "$link"
    chmod 644 "$link"
    flattened=$((flattened + 1))
done < <(find "$app" -type l -print0)

[ "$flattened" -gt 0 ] && echo "resolved $flattened symlinks jpackage would have flattened for us"

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

if [ "$hardened" -eq 0 ] && [ "$flattened" -eq 0 ]; then
    echo "the bundle is already notarizable: nothing to harden, no symlinks to resolve"
    exit 0
fi

# Both repairs edit sealed resources, so the seals are rewritten — innermost first, because the
# app's seal records the runtime's cdhash and would be stale the moment the runtime is re-signed.
#
# The runtime keeps exactly the signature jpackage gave it: its own identifier, a hardened runtime,
# and *no* entitlements. The identifier is read back off the bundle rather than written down here,
# so this cannot drift from whatever jpackage decides to call it.
runtime="$app/Contents/runtime"
if [ -d "$runtime" ]; then
    runtime_id="$(codesign -dv --verbose=2 "$runtime" 2>&1 | sed -n 's/^Identifier=//p')"
    codesign --force --timestamp --options runtime \
        --identifier "$runtime_id" -s "$identity" "$runtime"
fi

codesign --force --timestamp --options runtime \
    --entitlements "$entitlements" -s "$identity" "$app"
echo "hardened $hardened embedded binaries, resolved $flattened symlinks, re-signed the bundle"
