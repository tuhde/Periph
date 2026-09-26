#!/usr/bin/env python3
"""Generate the mip package files that make python/periph installable with
`mpremote mip install github:tuhde/Periph/python/...`.

    python/periph/package.json                     whole library
    python/periph/connection/package.json          connections only
    python/periph/chips/<category>/package.json    one category
    python/periph/chips/<category>/<chip>.json     one chip

mip is MicroPython-only, so *_circuitpython, *_linux and *_mock files are
never listed. A chip package holds the core (periph/__init__.py and what it
imports), the chip module plus every periph module it imports, and the
*_auto / *_micropython connection files for each transport the chip uses
(from the spec's "Transport" section and the connection imports in its
examples). Categories and the whole library are unions of those.

Every file is listed as an absolute github: URL. mip rewrites those with the
version the user asked for (`...@v1.2.1`, default HEAD), so an install at a
tag gets every file from that tag. Relative URLs would need `../` segments,
which raw.githubusercontent.com only answers with a redirect.

Usage:
    python3 python/scripts/generate-mip-packages.py           # write files
    python3 python/scripts/generate-mip-packages.py --check   # exit 1 if stale
"""
import ast
import json
import re
import sys
from pathlib import Path

PYTHON_DIR = Path(__file__).resolve().parent.parent
REPO_DIR = PYTHON_DIR.parent
PKG_DIR = PYTHON_DIR / "periph"
CHIPS_DIR = PKG_DIR / "chips"
CONN_DIR = PKG_DIR / "connection"
EXAMPLES_DIR = PYTHON_DIR / "examples"
SPECS_DIR = REPO_DIR / "specs"

GITHUB_BASE = "github:tuhde/Periph/python/"
NON_MICROPYTHON = ("_circuitpython", "_linux", "_mock")
# periph.connection.input_pin / output_pin import abc, which MicroPython
# firmware doesn't ship; micropython-lib publishes it on the mip index.
DEPS = [["abc", "latest"]]

# Transport headings in a spec's "## Transport..." section -> connection prefix
SPEC_TRANSPORTS = [
    (re.compile(r"I²C|I2C"), "i2c"),
    (re.compile(r"SMBus"), "smbus"),
    (re.compile(r"\bSPI\b"), "spi"),
    (re.compile(r"UART"), "uart"),
    (re.compile(r"NeoPixel"), "neopixel"),
    (re.compile(r"SiPo"), "sipo"),
    (re.compile(r"DHTxx"), "dhtxx"),
]


def is_micropython_file(path):
    return path.suffix == ".py" and not path.stem.endswith(NON_MICROPYTHON)


def rel(path):
    """Path relative to python/, as installed on the device (periph/...)."""
    return path.relative_to(PYTHON_DIR).as_posix()


def module_file(dotted):
    """periph.a.b -> python/periph/a/b.py or python/periph/a/b/__init__.py."""
    base = PYTHON_DIR.joinpath(*dotted.split("."))
    for cand in (base.with_suffix(".py"), base / "__init__.py"):
        if cand.is_file():
            return cand
    return None


def module_name(path):
    parts = list(path.relative_to(PYTHON_DIR).with_suffix("").parts)
    if parts[-1] == "__init__":
        parts.pop()
    return ".".join(parts)


def direct_imports(path):
    """periph files imported anywhere in `path` (top level or inside functions)."""
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    package = module_name(path)
    if path.name != "__init__.py":
        package = package.rpartition(".")[0]
    found = set()
    for node in ast.walk(tree):
        names = []
        if isinstance(node, ast.Import):
            names = [a.name for a in node.names]
        elif isinstance(node, ast.ImportFrom):
            if node.level:
                base = package.split(".")
                base = base[: len(base) - (node.level - 1)]
                mod = ".".join(base + ([node.module] if node.module else []))
            else:
                mod = node.module or ""
            names = [mod] + [f"{mod}.{a.name}" for a in node.names]  # `from pkg import submodule`
        for name in names:
            if name == "periph" or name.startswith("periph."):
                f = module_file(name)
                if f is not None:
                    found.add(f)
    return found


def closure(files):
    """files plus everything they import, MicroPython files only."""
    todo = [f for f in files if is_micropython_file(f)]
    seen = set()
    while todo:
        f = todo.pop()
        if f in seen:
            continue
        seen.add(f)
        todo.extend(d for d in direct_imports(f) if is_micropython_file(d) and d not in seen)
    return seen


def spec_transports(category, chip):
    norm = chip.replace("_", "").replace("-", "")
    for spec in (SPECS_DIR / category).glob("*.md"):
        if spec.stem.replace("_", "").replace("-", "") != norm:
            continue
        section = re.search(r"^## Transport.*?(?=^## )", spec.read_text(encoding="utf-8"), re.M | re.S)
        if not section:
            return set()
        headings = " ".join(re.findall(r"^### (.+)$", section.group(0), re.M))
        return {t for rx, t in SPEC_TRANSPORTS if rx.search(headings)}
    return set()


def example_connection_files(category, chip):
    files = set()
    for example in sorted((EXAMPLES_DIR / category / chip).glob("*.py")):
        files |= {f for f in direct_imports(example) if f.parent == CONN_DIR}
    return files


def transport_files(transport):
    return {f for f in (CONN_DIR / f"{transport}_auto.py", CONN_DIR / f"{transport}_micropython.py") if f.is_file()}


def chips():
    """(category, chip name, module file) for every chip with examples or a public module."""
    found = {}
    for cat_dir in sorted(d for d in CHIPS_DIR.iterdir() if d.is_dir() and d.name != "__pycache__"):
        cat = cat_dir.name
        names = {p.stem for p in cat_dir.glob("*.py") if not p.stem.startswith("_")}
        names |= {d.name for d in (EXAMPLES_DIR / cat).glob("*") if d.is_dir() and d.name != "__pycache__"}
        for name in sorted(names):
            module = next((m for m in (cat_dir / f"{name}.py", cat_dir / f"_{name}.py") if m.is_file()), None)
            if module is None:
                sys.exit(f"error: examples/{cat}/{name} has no chip module in periph/chips/{cat}/")
            found[(cat, name)] = module
    return found


def package(files, version):
    return {
        "urls": [[rel(f), GITHUB_BASE + rel(f)] for f in sorted(files, key=rel)],
        "deps": DEPS,
        "version": version,
    }


def main():
    check = "--check" in sys.argv[1:]
    version = re.search(r'^version = "(.*)"', (PYTHON_DIR / "pyproject.toml").read_text(), re.M).group(1)

    core = closure({PKG_DIR / "__init__.py"})
    outputs = {}
    by_category = {}

    for (cat, name), module in chips().items():
        conn = example_connection_files(cat, name)
        for t in spec_transports(cat, name) | {f.stem.split("_")[0] for f in conn}:
            conn |= transport_files(t)
        files = core | closure({module} | conn)
        outputs[CHIPS_DIR / cat / f"{name}.json"] = package(files, version)
        by_category.setdefault(cat, set()).update(files)

    for cat, files in by_category.items():
        outputs[CHIPS_DIR / cat / "package.json"] = package(files, version)

    conn_all = {f for f in CONN_DIR.glob("*.py") if is_micropython_file(f)}
    outputs[CONN_DIR / "package.json"] = package(core | closure(conn_all), version)

    everything = {f for f in PKG_DIR.rglob("*.py") if "__pycache__" not in f.parts and is_micropython_file(f)}
    outputs[PKG_DIR / "package.json"] = package(everything, version)

    # Package files that no longer correspond to anything (removed chip / category)
    existing = set(PKG_DIR.glob("package.json")) | set(CONN_DIR.glob("package.json")) | set(CHIPS_DIR.glob("*/*.json"))
    stale = existing - set(outputs)

    rendered = {p: json.dumps(d, indent=2, ensure_ascii=False) + "\n" for p, d in outputs.items()}
    changed = [p for p, text in rendered.items() if not p.is_file() or p.read_text(encoding="utf-8") != text]

    if check:
        for p in sorted(changed):
            print(f"out of date: {rel(p)}")
        for p in sorted(stale):
            print(f"stale: {rel(p)}")
        if changed or stale:
            print("Run python3 python/scripts/generate-mip-packages.py", file=sys.stderr)
            sys.exit(1)
        print(f"mip packages up to date ({len(rendered)} files)")
        return

    for p in changed:
        p.write_text(rendered[p], encoding="utf-8")
    for p in stale:
        p.unlink()
    print(f"wrote {len(changed)} of {len(rendered)} mip package files, removed {len(stale)}")


if __name__ == "__main__":
    main()
