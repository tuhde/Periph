#!/usr/bin/env bash
# Publish all periph npm packages to npmjs.com.
# Mirrors the nodejs-publish job in .github/workflows/release.yml.
#
# Usage:  ./publish.sh <version>          e.g. ./publish.sh 1.2.3
#                                              ./publish.sh v1.2.3
# Auth:   npm login   OR   export NODE_AUTH_TOKEN=<token>

set -euo pipefail

VERSION="${1:?Usage: $(basename "$0") <version>}"
VERSION="${VERSION#v}"          # strip leading 'v' if present

cd "$(dirname "$0")"           # run from nodejs/ regardless of call site

CONTRIB_DIRS=( packages/node-red-contrib-periph-*/ )
TOTAL=$(( ${#CONTRIB_DIRS[@]} + 1 ))   # +1 for periph core

echo "=== periph npm publish — v${VERSION} (${TOTAL} packages) ==="
echo ""

# ── 1. Stamp version in every package.json ───────────────────────────────────
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

# ── 2. Generate placeholder READMEs (skips packages that already have one) ───
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

# ── 3. Publish periph core ────────────────────────────────────────────────────
echo ""
echo "Publishing periph (core)..."
npm publish --workspace periph --access public

# ── 4. Publish Node-RED contrib packages ─────────────────────────────────────
echo ""
echo "Publishing contrib packages (10 s between each)..."
failed=()
for d in "${CONTRIB_DIRS[@]}"; do
    name=$(python3 -c "import json; print(json.load(open('${d}package.json'))['name'])")
    printf '  %-52s' "$name"
    if out=$(npm publish --workspace "$name" --access public 2>&1); then
        echo "OK"
    else
        echo "SKIPPED"
        echo "$out" | sed 's/^/      /'
        failed+=("$name")
    fi
    sleep 10
done

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
skipped=${#failed[@]}
published=$(( TOTAL - skipped ))
echo "=== Done: ${published}/${TOTAL} published ==="
if (( skipped > 0 )); then
    echo "Skipped (already at this version or error):"
    printf '  %s\n' "${failed[@]}"
fi
