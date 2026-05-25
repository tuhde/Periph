#!/usr/bin/env bash
# Publish periph npm packages to npmjs.com.
# Mirrors the nodejs-publish job in .github/workflows/release.yml.
#
# Usage:  ./publish.sh <version> [package]
#
#   <version>   required — e.g. 1.2.3 or v1.2.3
#   [package]   optional — publish only this package; omit to publish all
#               accepted forms:
#                 periph                           core driver package
#                 node-red-contrib-periph-power    full npm name
#                 power                            category short name
#
# Auth:   npm login   OR   export NODE_AUTH_TOKEN=<token>

set -euo pipefail

VERSION="${1:?Usage: $(basename "$0") <version> [package]}"
VERSION="${VERSION#v}"
FILTER="${2:-}"

cd "$(dirname "$0")"

CONTRIB_DIRS=( packages/node-red-contrib-periph-*/ )

# ── Resolve a package arg to its npm name ─────────────────────────────────────
# Accepts: exact npm name, directory name, or category short name (e.g. "power")
resolve_package() {
    python3 - "$1" <<'PYEOF'
import json, pathlib, sys
target = sys.argv[1]
for f in sorted(pathlib.Path('packages').glob('*/package.json')):
    pkg = json.load(f.open())
    name = pkg['name']
    if name == target or f.parent.name == target or name.endswith('-' + target):
        print(name)
        sys.exit(0)
print(f'error: package not found: {target}', file=sys.stderr)
sys.exit(1)
PYEOF
}

# ── Validate filter early ─────────────────────────────────────────────────────
if [[ -n "$FILTER" ]]; then
    PACKAGE=$(resolve_package "$FILTER")
    echo "=== periph npm publish — v${VERSION} (${PACKAGE} only) ==="
else
    TOTAL=$(( ${#CONTRIB_DIRS[@]} + 1 ))
    echo "=== periph npm publish — v${VERSION} (${TOTAL} packages) ==="
fi
echo ""

# ── 1. Stamp version in every package.json ────────────────────────────────────
echo "Stamping version..."
python3 - "$VERSION" <<'PYEOF'
import json, pathlib, sys
version = sys.argv[1]
for f in sorted(pathlib.Path('packages').glob('*/package.json')):
    pkg = json.loads(f.read_text())
    pkg['version'] = version
    f.write_text(json.dumps(pkg, indent=2) + '\n')
    print(f'  {f.parent.name}')
PYEOF

# ── 2. Generate placeholder READMEs ───────────────────────────────────────────
echo ""
echo "READMEs..."
python3 - <<'PYEOF'
import json, pathlib
for d in sorted(pathlib.Path('packages').glob('node-red-contrib-periph-*/')):
    if (d / 'README.md').exists():
        print(f'  {d.name}  (existing)')
        continue
    pkg = json.loads((d / 'package.json').read_text())
    name, desc = pkg['name'], pkg['description']
    (d / 'README.md').write_text(
        f'# {name}\n\n'
        f'{desc} — part of the [Periph](https://github.com/tuhde/Periph) library.\n\n'
        f'> **Coming soon.** Nodes will be added as chips in this category'
        f' are implemented.\n\n'
        f'## Install\n\n'
        f'```sh\nnpm install {name}\n```\n\n'
        f'## Links\n\n'
        f'- [GitHub](https://github.com/tuhde/Periph)\n'
        f'- [All supported chips](https://github.com/tuhde/Periph#supported-chips)\n'
    )
    print(f'  {d.name}  (generated)')
PYEOF
echo ""

# ── 3. Publish ────────────────────────────────────────────────────────────────
publish_one() {
    local name="$1" no_sleep="${2:-}"
    printf '  %-52s' "$name"
    if out=$(npm publish --workspace "$name" --access public 2>&1); then
        echo "OK"
        return 0
    else
        echo "SKIPPED"
        echo "$out" | sed 's/^/      /'
        return 1
    fi
}

if [[ -n "$FILTER" ]]; then
    # ── Single package ────────────────────────────────────────────────────────
    echo "Publishing..."
    publish_one "$PACKAGE"
    echo ""
    echo "=== Done ==="
else
    # ── All packages ──────────────────────────────────────────────────────────
    echo "Publishing periph (core)..."
    publish_one periph
    echo ""
    echo "Publishing contrib packages (10 s between each)..."
    failed=()
    for d in "${CONTRIB_DIRS[@]}"; do
        name=$(python3 -c "import json; print(json.load(open('${d}package.json'))['name'])")
        publish_one "$name" || failed+=("$name")
        sleep 10
    done
    echo ""
    skipped=${#failed[@]}
    published=$(( TOTAL - skipped ))
    echo "=== Done: ${published}/${TOTAL} published ==="
    if (( skipped > 0 )); then
        echo "Skipped:"
        printf '  %s\n' "${failed[@]}"
    fi
fi
