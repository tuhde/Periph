#!/usr/bin/env bash
# Publish the periph Rust crate to crates.io.
# Mirrors the rust-publish job in .github/workflows/release.yml.
# Use this to retry a failed CI publish without re-tagging.
#
# Usage:  ./publish.sh <version>   e.g. ./publish.sh 1.2.3  or  ./publish.sh v1.2.3
# Auth:   cargo login   OR   export CARGO_REGISTRY_TOKEN=<token>

set -euo pipefail

VERSION="${1:?Usage: $(basename "$0") <version>}"
VERSION="${VERSION#v}"

cd "$(dirname "$0")/.."    # repo root

echo "=== periph crates.io publish — v${VERSION} ==="
echo ""

echo "Stamping version..."
python3 - "$VERSION" <<'PYEOF'
import re, pathlib, sys
version = sys.argv[1]
f = pathlib.Path('rust/periph/Cargo.toml')
f.write_text(re.sub(r'^version = ".*"', f'version = "{version}"', f.read_text(), flags=re.MULTILINE))
print(f'  rust/periph/Cargo.toml → {version}')
PYEOF

echo ""
echo "Publishing..."
cargo publish --manifest-path rust/periph/Cargo.toml --allow-dirty

echo ""
echo "=== done ==="
