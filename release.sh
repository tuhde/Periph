#!/usr/bin/env bash
# Stamp the version across all build systems, commit, tag, and push.
# Pushing the tag triggers the GitHub Actions release workflow.
#
# Usage:  ./release.sh <version>   e.g. ./release.sh 1.2.3  or  ./release.sh v1.2.3
#
# What this script does:
#   1. Stamps <version> in pyproject.toml, library.properties, Cargo.toml,
#      pom.xml, all package.json files, and INSTALL.md
#   2. Commits the changes on main
#   3. Creates tag v<version> and pushes branch + tag to all remotes
#
# What the triggered CI then does:
#   Python wheel/sdist, Arduino zip, npm publish, cargo publish,
#   JVM JARs, GitHub release creation

set -euo pipefail

VERSION="${1:?Usage: $(basename "$0") <version>}"
VERSION="${VERSION#v}"     # strip leading 'v' if present
TAG="v${VERSION}"

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

# ── Preflight checks ──────────────────────────────────────────────────────────
echo "=== preflight ==="

if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "error: version must be X.Y.Z (got: $VERSION)"
    exit 1
fi

BRANCH=$(git rev-parse --abbrev-ref HEAD)
if [[ "$BRANCH" != "main" ]]; then
    echo "error: must be on main branch (current: $BRANCH)"
    exit 1
fi

if ! git diff --quiet || ! git diff --staged --quiet; then
    echo "error: uncommitted changes — commit or stash first"
    git status --short
    exit 1
fi

if git rev-parse "$TAG" &>/dev/null 2>&1; then
    echo "error: tag $TAG already exists"
    exit 1
fi

echo "  branch : $BRANCH"
echo "  version: $VERSION"
echo "  tag    : $TAG"
echo ""

# ── Stamp version ─────────────────────────────────────────────────────────────
echo "=== stamping version ==="
python3 - "$VERSION" "$ROOT" <<'PYEOF'
import json, re, pathlib, sys

version = sys.argv[1]
root    = pathlib.Path(sys.argv[2])

def sed(path, pattern, replacement, count=0):
    f = root / path
    f.write_text(re.sub(pattern, replacement, f.read_text(), count=count, flags=re.MULTILINE))
    print(f'  {path}')

sed('python/pyproject.toml',  r'^version = ".*"', f'version = "{version}"')
sed('cpp/library.properties', r'^version=.*',     f'version={version}')
sed('rust/periph/Cargo.toml', r'^version = ".*"', f'version = "{version}"')

# JVM: first <version> element only (the parent POM version)
pom = root / 'jvm/pom.xml'
pom.write_text(re.sub(r'<version>[^<]+</version>', f'<version>{version}</version>',
                       pom.read_text(), count=1))
print('  jvm/pom.xml')

# Node.js: all package.json files under nodejs/packages/
pkg_files = sorted((root / 'nodejs/packages').glob('*/package.json'))
for f in pkg_files:
    pkg = json.loads(f.read_text())
    pkg['version'] = version
    f.write_text(json.dumps(pkg, indent=2) + '\n')
print(f'  nodejs/packages/*  ({len(pkg_files)} package.json files)')

# INSTALL.md: replace old version throughout the file
install = root / 'INSTALL.md'
content = install.read_text()
old = re.search(r'<!-- periph-version: ([^ >]+) -->', content).group(1)
install.write_text(content.replace(old, version))
print('  INSTALL.md')
PYEOF

# ── Commit ────────────────────────────────────────────────────────────────────
echo ""
echo "=== committing ==="
git add \
    python/pyproject.toml \
    cpp/library.properties \
    rust/periph/Cargo.toml \
    jvm/pom.xml \
    INSTALL.md \
    -- 'nodejs/packages/*/package.json'
git commit -m "chore: release ${TAG}"
echo "  $(git rev-parse --short HEAD)  chore: release ${TAG}"

# ── Tag and push main ─────────────────────────────────────────────────────────
echo ""
echo "=== tagging and pushing main ==="
git tag "$TAG"
git push all main
git push all "$TAG"

# ── Update arduino branch ──────────────────────────────────────────────────────
echo ""
echo "=== updating arduino branch ==="
ARDUINO_TAG="arduino-v${VERSION}"

git worktree add /tmp/periph-arduino arduino
AW=/tmp/periph-arduino

# Clear existing content (keep .git)
find "$AW" -mindepth 1 -not -path "$AW/.git" -not -path "$AW/.git/*" -delete

# Copy src/ and examples/ (Arduino only, no Zephyr)
cp -r cpp/src "$AW/src"
mkdir -p "$AW/examples"
for d in cpp/examples/*/; do
  name=$(basename "$d")
  [[ "$name" == *_Zephyr ]] && continue
  cp -r "$d" "$AW/examples/$name"
done

# library.properties with stamped version
sed "s/^version=.*/version=${VERSION}/" cpp/library.properties > "$AW/library.properties"

cd "$AW"
git add -A
if ! git diff --staged --quiet; then
  git commit -m "chore: release ${ARDUINO_TAG}"
fi
git tag "$ARDUINO_TAG"
cd "$ROOT"

git worktree remove /tmp/periph-arduino

git push all arduino
git push all "$ARDUINO_TAG"
echo "  ${ARDUINO_TAG} pushed"
echo ""
echo "=== done — GitHub Actions release workflow triggered by ${TAG} ==="
