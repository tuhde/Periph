#!/usr/bin/env bash
# Build the periph Python wheel and sdist.
# Mirrors the python-build job in .github/workflows/release.yml.
# Use this to retry a failed CI build or to produce artifacts locally.
# Output: python/dist/
#
# Usage:  ./build.sh <version>   e.g. ./build.sh 1.2.3  or  ./build.sh v1.2.3

set -euo pipefail

VERSION="${1:?Usage: $(basename "$0") <version>}"
VERSION="${VERSION#v}"

cd "$(dirname "$0")/.."    # repo root

echo "=== periph Python build — v${VERSION} ==="
echo ""

echo "Stamping version..."
python3 - "$VERSION" <<'PYEOF'
import re, pathlib, sys
version = sys.argv[1]
f = pathlib.Path('python/pyproject.toml')
f.write_text(re.sub(r'^version = ".*"', f'version = "{version}"', f.read_text(), flags=re.MULTILINE))
print(f'  python/pyproject.toml → {version}')
PYEOF

echo ""
echo "Building..."
rm -rf python/dist/
pip install --quiet build
python -m build python/

echo ""
echo "=== done ==="
ls -lh python/dist/
