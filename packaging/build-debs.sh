#!/usr/bin/env bash
# Build the Periph Debian packages (Debian 13 trixie).
#
# Usage: packaging/build-debs.sh VERSION [ARCH] [OUTDIR]
#
#   VERSION  package version without the leading v, e.g. 1.6.0
#   ARCH     amd64 (default: the build machine's architecture) builds
#            python3-periph, sigrok-decoders-periph (both Architecture: all)
#            and libperiph-dev for that architecture. Any other architecture
#            cross-builds libperiph-dev only; it needs crossbuild-essential-ARCH
#            and libgpiod-dev:ARCH installed.
#   OUTDIR   where the .deb files land (default: dist/debian)
#
# The packaging lives in packaging/debian. dpkg-buildpackage writes its output
# next to the source tree, so the script assembles a source tree from the
# files the packages need (tracked plus untracked-but-not-ignored) under
# OUTDIR/build instead of building in the repo itself.
set -euo pipefail

VERSION=${1:?usage: build-debs.sh VERSION [ARCH] [OUTDIR]}
BUILD_ARCH=$(dpkg --print-architecture)
ARCH=${2:-$BUILD_ARCH}
REPO=$(cd "$(dirname "$0")/.." && pwd)
OUT=$(mkdir -p "${3:-$REPO/dist/debian}" && cd "${3:-$REPO/dist/debian}" && pwd)
WORK=$OUT/build
SRC=$WORK/periph-$VERSION

cd "$REPO"

# --- sigrok decoder names must not clash with the ones libsigrokdecode ships
# (a file conflict in dpkg, and an ambiguous decoder id for sigrok-cli).
upstream=""
if dpkg -L libsigrokdecode4 >/dev/null 2>&1; then
    upstream=$(dpkg -L libsigrokdecode4 | sed -n 's|^/usr/share/libsigrokdecode/decoders/\([^/]*\)$|\1|p')
elif [ -d /usr/share/libsigrokdecode/decoders ]; then
    upstream=$(ls /usr/share/libsigrokdecode/decoders)
fi
ours=$(cd sigrok && for d in */; do [ "$d" = tests/ ] || echo "${d%/}"; done)
ids=$(grep -h "^[[:space:]]*id = " sigrok/*/pd.py | sed "s/.*id = ['\"]\([^'\"]*\)['\"].*/\1/")
clash=$(comm -12 <(sort <<<"$upstream") <(sort -u <<<"$ours"$'\n'"$ids") | grep . || true)
dups=$(sort <<<"$ids" | uniq -d)
if [ -n "$clash$dups" ]; then
    [ -n "$clash" ] && echo "error: sigrok decoders also shipped by libsigrokdecode4: $clash" >&2
    [ -n "$dups" ] && echo "error: duplicate sigrok decoder ids: $dups" >&2
    exit 1
fi
[ -n "$upstream" ] || echo "warning: libsigrokdecode4 not installed; decoder name clash check skipped" >&2

# --- source tree
rm -rf -- "$WORK"
mkdir -p "$SRC"
git ls-files -z --cached --others --exclude-standard -- \
        LICENSE NOTICE cpp/library.properties cpp/linux cpp/src python sigrok |
    xargs -0 -I{} sh -c '[ -e "$1" ] && printf "%s\0" "$1"' _ {} |
    tar --null -T - -cf - | tar -xf - -C "$SRC"
cp -r packaging/debian "$SRC/debian"

{
    echo "periph ($VERSION) trixie; urgency=medium"
    echo
    echo "  * Release v$VERSION: https://github.com/tuhde/Periph/releases/tag/v$VERSION"
    echo
    echo " -- Till Uhde <till@uhde.com>  $(git log -1 --format=%cD)"
} > "$SRC/debian/changelog"

# --- build
cd "$SRC"
if [ "$ARCH" = "$BUILD_ARCH" ]; then
    dpkg-buildpackage -b -us -uc
else
    dpkg-buildpackage -B -a"$ARCH" -us -uc
fi

cd "$WORK"
if command -v lintian >/dev/null; then
    lintian --fail-on error ./*.changes
fi
mv ./*.deb "$OUT/"
rm -rf -- "$WORK"
ls -1 "$OUT"/*.deb
