#!/usr/bin/env bash
# Stamp the version across all build systems, commit, tag, and push.
# Pushing the tag triggers the GitHub Actions release workflow.
#
# Usage:  ./release.sh <version>   e.g. ./release.sh 1.2.3  or  ./release.sh v1.2.3
#
# What this script does:
#   1. Stamps <version> in pyproject.toml, library.properties, Cargo.toml,
#      jvm/pom.xml (+ each child module's <parent> version), JVM JBang
#      //DEPS lines under jvm/examples and jvm/tests, all package.json
#      files (+ regenerated package-lock.json), and INSTALL.md
#   2. Regenerates python/README.md (python/scripts/generate-readme.js),
#      rust/periph/README.md (rust/scripts/generate-readme.js), and
#      STATS.md (scripts/generate_stats.py)
#   3. Commits the changes on main
#   4. Creates tag v<version> and pushes branch + tag to all remotes
#
# What the triggered CI then does:
#   Python wheel/sdist, Arduino zip, Arduino library push to
#   tuhde/Periph-Arduino, npm publish, cargo publish, JVM JARs,
#   GitHub release creation

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
pom_text = pom.read_text()
old_jvm_version = re.search(r'<version>([^<]+)</version>', pom_text).group(1)
pom.write_text(re.sub(r'<version>[^<]+</version>', f'<version>{version}</version>',
                       pom_text, count=1))
print('  jvm/pom.xml')

# JVM: child modules each hardcode the parent version in their own <parent> block
jvm_modules = ['periph-connection', 'periph-java', 'periph-kotlin', 'periph-groovy']
for module in jvm_modules:
    sed(f'jvm/{module}/pom.xml',
        r'(?s)(<parent>.*?<version>)[^<]+(</version>.*?</parent>)',
        lambda m: f'{m.group(1)}{version}{m.group(2)}')
print(f'  jvm/*/pom.xml  ({len(jvm_modules)} child modules)')

# JVM: JBang example/test scripts pin //DEPS it.uhde:periph-<module>:<version>
jbang_files = sorted((root / 'jvm/examples').rglob('*')) + sorted((root / 'jvm/tests').rglob('*'))
jbang_pattern = re.compile(
    r'(//DEPS it\.uhde:periph-(?:connection|java|kotlin|groovy):)' + re.escape(old_jvm_version))
jbang_touched = 0
for f in jbang_files:
    if not f.is_file():
        continue
    text = f.read_text()
    new_text = jbang_pattern.sub(rf'\g<1>{version}', text)
    if new_text != text:
        f.write_text(new_text)
        jbang_touched += 1
print(f'  jvm/examples/**, jvm/tests/**  ({jbang_touched} //DEPS files)')

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

# Node.js: regenerate package-lock.json so it matches the stamped package.json
# versions (npm ci fails on a stale lockfile otherwise)
echo "  nodejs/package-lock.json"
(cd "$ROOT/nodejs" && npm install --package-lock-only --workspaces --no-audit --no-fund >/dev/null)

# Python: regenerate the package README (the file PyPI renders as the
# project description) from the stamped pyproject.toml + the chip drivers
# actually shipped, so it never drifts stale like it did before this existed.
echo "  python/README.md"
node "$ROOT/python/scripts/generate-readme.js"

# Rust: regenerate the crate README (the file crates.io renders as the
# package page) from the stamped Cargo.toml + the chip drivers actually
# shipped, so it never drifts stale like it did before this existed.
echo "  rust/periph/README.md"
node "$ROOT/rust/scripts/generate-readme.js"

# ── Regenerate stats ──────────────────────────────────────────────────────────
echo ""
echo "=== regenerating STATS.md ==="
python3 "$ROOT/scripts/generate_stats.py"

# ── Commit ────────────────────────────────────────────────────────────────────
echo ""
echo "=== committing ==="
git add \
    python/pyproject.toml \
    python/README.md \
    cpp/library.properties \
    rust/periph/Cargo.toml \
    rust/periph/README.md \
    jvm/pom.xml \
    jvm/periph-connection/pom.xml \
    jvm/periph-java/pom.xml \
    jvm/periph-kotlin/pom.xml \
    jvm/periph-groovy/pom.xml \
    jvm/examples \
    jvm/tests \
    nodejs/package-lock.json \
    INSTALL.md \
    STATS.md \
    -- 'nodejs/packages/*/package.json'
git commit -m "chore: release ${TAG}"
echo "  $(git rev-parse --short HEAD)  chore: release ${TAG}"

# ── Tag and push main ─────────────────────────────────────────────────────────
echo ""
echo "=== tagging and pushing main ==="
git tag "$TAG"
git push all main
git push all "$TAG"

# Arduino: the release workflow's arduino-publish job pushes the Library
# Manager layout to tuhde/Periph-Arduino (deploy key lives in CI only).

echo ""
echo "=== done — GitHub Actions release workflow triggered by ${TAG} ==="
