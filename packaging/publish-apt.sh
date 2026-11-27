#!/usr/bin/env bash
# Add Debian packages to the Periph APT repository checkout (reprepro).
#
# Usage: packaging/publish-apt.sh APT_REPO_DIR DEB...
#
# APT_REPO_DIR is a checkout of tuhde/periph-apt (an empty directory works
# for a first run). The script syncs the reprepro configuration and the landing
# page from packaging/apt-repo, exports the public signing key, and includes
# the packages into the trixie suite. GitHub Pages serves APT_REPO_DIR/docs.
#
# Signing uses the default secret key of the current GNUPGHOME (import the
# repository key first). Re-running with the same version is a no-op for
# packages the repository already lists at that version, so a retried release
# doesn't fail on rebuilt (not byte-identical) .deb files. reprepro keeps one
# version per package, so older .debs drop out of the site.
set -euo pipefail

REPO=${1:?usage: publish-apt.sh APT_REPO_DIR DEB...}
shift
[ $# -gt 0 ] || { echo "error: no .deb files given" >&2; exit 1; }
HERE=$(cd "$(dirname "$0")" && pwd)
SUITE=trixie

gpg --batch --list-secret-keys --with-colons | grep -q '^sec' ||
    { echo "error: no secret key in GNUPGHOME to sign the repository" >&2; exit 1; }

mkdir -p "$REPO/conf" "$REPO/docs"
cp "$HERE"/apt-repo/conf/* "$REPO/conf/"
cp "$HERE/apt-repo/index.html" "$REPO/docs/index.html"
touch "$REPO/docs/.nojekyll"
gpg --batch --yes --export > "$REPO/docs/periph-archive-keyring.gpg"
gpg --batch --yes --armor --export > "$REPO/docs/periph-archive-keyring.asc"

for deb in "$@"; do
    deb=$(cd "$(dirname "$deb")" && pwd)/$(basename "$deb")
    pkg=$(dpkg-deb -f "$deb" Package)
    ver=$(dpkg-deb -f "$deb" Version)
    arch=$(dpkg-deb -f "$deb" Architecture)
    # Architecture: all packages are listed under every architecture.
    listarch=$arch; [ "$arch" = all ] && listarch=amd64
    have=$(reprepro -b "$REPO" -A "$listarch" --list-format '${version}\n' list "$SUITE" "$pkg" 2>/dev/null || true)
    if [ "$have" = "$ver" ]; then
        echo "$pkg $ver ($arch) already published, skipped"
        continue
    fi
    reprepro -b "$REPO" includedeb "$SUITE" "$deb"
done
reprepro -b "$REPO" export "$SUITE"
reprepro -b "$REPO" deleteunreferenced
reprepro -b "$REPO" list "$SUITE"
