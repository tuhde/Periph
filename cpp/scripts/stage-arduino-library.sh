#!/usr/bin/env bash
# Usage:
#   cpp/scripts/stage-arduino-library.sh <dest-dir> [version]
#
# Writes the Arduino Library Manager layout of cpp/ into <dest-dir>: the exact
# content of the arduino orphan branch and of the release zip.
#
#   library.properties   version= stamped with [version] when given
#   LICENSE              arduino-lint LD002
#   README.md            regenerated (generate-readme.js)
#   keywords.txt         regenerated (generate-keywords.js)
#   src/                 cpp/src/ (all chips + connections, src/Periph.h umbrella)
#   examples/            cpp/examples/arduino/<category>/<Chip>/<tier>/<tier>.ino
#                        restaged as <category>/<Chip>_<Tier>/<Chip>_<Tier>.ino
#                        (sketch dir name must match the .ino; the category
#                        level becomes a submenu under File > Examples > Periph)
#
# <dest-dir> must exist; its content (except .git) is replaced. Used by
# release.sh, .github/workflows/release.yml and the CI arduino-lint step.

set -euo pipefail
DEST="${1:?Usage: $(basename "$0") <dest-dir> [version]}"
VERSION="${2:-}"
CPP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
REPO_DIR="$(dirname "$CPP_DIR")"

[ -d "$DEST" ] || { echo "error: $DEST does not exist" >&2; exit 1; }

# Clear existing content (keep .git, so this works on a worktree)
find "$DEST" -mindepth 1 -not -path "$DEST/.git" -not -path "$DEST/.git/*" -delete

cp -r "$CPP_DIR/src" "$DEST/src"

mkdir -p "$DEST/examples"
find "$CPP_DIR/examples/arduino" -name "*.ino" | while read -r ino; do
    chip_dir="$(dirname "$(dirname "$ino")")"
    chip="$(basename "$chip_dir")"
    category="$(basename "$(dirname "$chip_dir")")"
    tier="$(basename "$(dirname "$ino")")"
    sketch="${chip}_${tier^}"
    mkdir -p "$DEST/examples/$category/$sketch"
    cp "$ino" "$DEST/examples/$category/$sketch/$sketch.ino"
done

if [ -n "$VERSION" ]; then
    sed "s/^version=.*/version=${VERSION}/" "$CPP_DIR/library.properties" > "$DEST/library.properties"
else
    cp "$CPP_DIR/library.properties" "$DEST/library.properties"
fi

cp "$REPO_DIR/LICENSE" "$DEST/LICENSE"

node "$CPP_DIR/scripts/generate-readme.js" > /dev/null
cp "$CPP_DIR/README.md" "$DEST/README.md"

node "$CPP_DIR/scripts/generate-keywords.js" > /dev/null
cp "$CPP_DIR/keywords.txt" "$DEST/keywords.txt"
