#!/bin/bash
#
# Notarizes the application itself and staples the ticket to the bundle.
#
# The disk image gets its own ticket at the end of the release (see `stapleDmg`), and while the app
# sits inside that image it is covered by it. What is not covered is the app *after* someone drags
# it to /Applications: a bundle carries only the ticket stapled to it, and the image's ticket stays
# with the image. So a first launch on a machine with no network — the one case stapling exists for
# — cannot verify a build that was only stapled as a DMG.
#
# Hence two submissions, which is what Apple's own instructions describe: notarize the app, staple
# it, and only then build the image around the stapled copy and notarize that. Ordering is the whole
# point — a bundle stapled after the image is built is not the bundle inside the image.
#
# The app is submitted as a zip because notarytool takes archives, not directories, and `ditto
# -c -k --keepParent` is the one that preserves the symlinks and extended attributes a bundle is
# made of. The zip is a shipping container: it is deleted afterwards, and nothing is stapled to it.
#
# Usage: notarize-app.sh <Forest.app> <keychain profile>
set -euo pipefail

app="$1"
profile="$2"

zip="${app%.app}-notarize.zip"
rm -f "$zip"
/usr/bin/ditto -c -k --keepParent "$app" "$zip"

# `--wait` blocks until Apple has an answer, which is minutes. Its exit code is not a reliable
# verdict on its own, so the status is read out of the output as well.
verdict="$(xcrun notarytool submit "$zip" --keychain-profile "$profile" --wait 2>&1)"
echo "$verdict"
rm -f "$zip"

if ! grep -q "status: Accepted" <<<"$verdict"; then
    echo "the app was not accepted; nothing has been stapled" >&2
    exit 1
fi

xcrun stapler staple "$app"
xcrun stapler validate "$app"
