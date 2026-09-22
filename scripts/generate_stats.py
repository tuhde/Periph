#!/usr/bin/env python3
"""Regenerate STATS.md: non-blank line counts per language, platform, category, and chip.

Usage:
    python3 scripts/generate_stats.py

Run manually after any significant source change, and automatically by
release.sh as part of stamping a release. Counts non-blank lines only
(comments count, blank lines don't) via a plain per-file scan -- no
external tools required.

Scope:
  - "source"   = chip driver code + connection/transport code + platform
                 block/node wrappers (UIFlow 1/2, Node-RED) + sigrok decoders
  - "tests"    = everything under a */tests/ tree
  - "examples" = everything under a */examples/ tree

Chip driver code is shared across a language's platforms (e.g. C++'s
chips/ is identical for Arduino and Zephyr), so it is counted once under
that language's totals, not once per platform. Connection/transport code,
examples, and tests genuinely differ per platform and are broken out
that way. JVM is the one exception: Java/Kotlin/Groovy are three
independent re-implementations, not a shared driver, so JVM's "platform"
axis (Java/Kotlin/Groovy) carries its own driver line counts too.
"""

import pathlib
import re
import sys
from collections import defaultdict

ROOT = pathlib.Path(__file__).resolve().parent.parent

SCOPES = ("source", "tests", "examples")


def norm(s: str) -> str:
    return re.sub(r"[-_]", "", s).lower()


# --- Canonical chip registry, built from specs/<category>/<chip>.md ---------

DISPLAY_OVERRIDES = {
    "mpu6050": "MPU-6050",
    "mpu9250": "MPU-9250",
    "mpu9255": "MPU-9255",
    "rfm9x": "RFM9x",
}


def build_chip_registry():
    registry = {}  # norm(chip_id) -> (category, display_name)
    for f in sorted((ROOT / "specs").glob("*/*.md")):
        if f.stem.startswith("_"):
            continue
        cat = f.parent.name
        key = norm(f.stem)
        display = DISPLAY_OVERRIDES.get(f.stem.lower(), f.stem.upper())
        registry[key] = (cat, display)
    return registry


CHIPS = build_chip_registry()


def lookup_chip(name_fragment: str):
    """Match a directory/file stem against the chip registry. Returns
    (category, display_name) or (None, None) if it isn't a chip name."""
    key = norm(name_fragment)
    return CHIPS.get(key, (None, None))


# --- Line counting -----------------------------------------------------------


def count_lines(path: pathlib.Path) -> int:
    try:
        text = path.read_text(errors="ignore")
    except (UnicodeDecodeError, OSError):
        return 0
    return sum(1 for line in text.splitlines() if line.strip())


EXCLUDE_DIR_NAMES = {"__pycache__", "node_modules", "target", ".generator", "build"}


def iter_files(base: pathlib.Path, extensions):
    if not base.exists():
        return
    for p in sorted(base.rglob("*")):
        if not p.is_file():
            continue
        if p.suffix.lstrip(".") not in extensions:
            continue
        if any(part in EXCLUDE_DIR_NAMES for part in p.parts):
            continue
        yield p


# --- Records -------------------------------------------------------------
# Each record: dict(language, platform, category, chip, scope, lines)

records = []


def add(language, platform, category, chip, scope, lines):
    if lines <= 0:
        return
    records.append(
        dict(language=language, platform=platform, category=category, chip=chip, scope=scope, lines=lines)
    )


# --- Python ------------------------------------------------------------------

def gather_python():
    lang = "Python"

    # Shared driver source (language-level, no platform split)
    for cat_dir in sorted((ROOT / "python/periph/chips").glob("*")):
        if not cat_dir.is_dir():
            continue
        for f in iter_files(cat_dir, {"py"}):
            category, chip = lookup_chip(f.stem)
            add(lang, None, category, chip, "source", count_lines(f))

    # Connection/transport source, split by platform suffix
    conn_platform = {
        "micropython": "MicroPython",
        "circuitpython": "CircuitPython",
        "linux": "Linux",
        "mock": "Mocks (unit tests)",
        "auto": "Generic (auto-dispatch)",
    }
    for f in iter_files(ROOT / "python/periph/connection", {"py"}):
        suffix = f.stem.rsplit("_", 1)[-1]
        platform = conn_platform.get(suffix, "Generic (auto-dispatch)")
        add(lang, platform, None, None, "source", count_lines(f))

    # Package root (__init__.py directly under python/periph/)
    for f in (ROOT / "python/periph").glob("*.py"):
        add(lang, None, None, None, "source", count_lines(f))

    # UIFlow 1 block source
    for chip_dir in (ROOT / "python/uiflow1").glob("*/*"):
        if not chip_dir.is_dir():
            continue
        category, chip = lookup_chip(chip_dir.name)
        for f in iter_files(chip_dir, {"py"}):
            add(lang, "UIFlow 1", category, chip, "source", count_lines(f))

    # UIFlow 2 wrapper source
    for chip_dir in (ROOT / "python/uiflow2").glob("*/*"):
        if not chip_dir.is_dir():
            continue
        category, chip = lookup_chip(chip_dir.name)
        for f in iter_files(chip_dir, {"py"}):
            add(lang, "UIFlow 2", category, chip, "source", count_lines(f))

    # Examples (single generic tree, no platform split)
    for chip_dir in (ROOT / "python/examples").glob("*/*"):
        if not chip_dir.is_dir():
            continue
        category, chip = lookup_chip(chip_dir.name)
        for f in iter_files(chip_dir, {"py"}):
            add(lang, None, category, chip, "examples", count_lines(f))

    # Tests, split by platform suffix
    test_platform_by_suffix = {"cp": "CircuitPython", "linux": "Linux", "unit": "Linux"}
    for cat_dir in sorted((ROOT / "python/tests").glob("*")):
        if not cat_dir.is_dir():
            continue
        for f in iter_files(cat_dir, {"py"}):
            m = re.match(r"^(.*)_test(?:_(\w+))?$", f.stem)
            if not m:
                continue
            chip_name, suffix = m.group(1), m.group(2)
            category, chip = lookup_chip(chip_name)
            platform = test_platform_by_suffix.get(suffix, "MicroPython")
            add(lang, platform, category, chip, "tests", count_lines(f))


# --- C++ -----------------------------------------------------------------

def gather_cpp():
    lang = "C++"

    for cat_dir in sorted((ROOT / "cpp/src/chips").glob("*")):
        if not cat_dir.is_dir():
            continue
        for f in iter_files(cat_dir, {"h", "hpp", "cpp", "cc"}):
            category, chip = lookup_chip(f.stem)
            add(lang, None, category, chip, "source", count_lines(f))

    conn_suffixes = {
        "ESPIDF": "ESP-IDF",
        "Linux": "Linux GCC",
        "Zephyr": "Zephyr",
        "PicoSDK": "Pico SDK",
        "Mock": "Mocks (unit tests)",
        "Arduino": "Arduino",
    }
    for f in iter_files(ROOT / "cpp/src/connection", {"h", "hpp", "cpp", "cc"}):
        if f.stem == "Connection":
            platform = "Generic (base interface)"
        else:
            platform = "Arduino"
            for suf, name in conn_suffixes.items():
                if f.stem.endswith(suf):
                    platform = name
                    break
        add(lang, platform, None, None, "source", count_lines(f))

    example_platform = {
        "linux": "Linux GCC",
        "arduino": "Arduino",
        "zephyr": "Zephyr",
        "espidf": "ESP-IDF",
        "picosdk": "Pico SDK",
    }
    for plat_dir in (ROOT / "cpp/examples").glob("*"):
        platform = example_platform.get(plat_dir.name, plat_dir.name)
        for chip_dir in plat_dir.glob("*/*"):
            if not chip_dir.is_dir():
                continue
            category, chip = lookup_chip(chip_dir.name)
            for f in iter_files(chip_dir, {"h", "hpp", "cpp", "cc", "ino"}):
                add(lang, platform, category, chip, "examples", count_lines(f))

    test_platform_by_suffix = {
        "linux": "Linux GCC",
        "zephyr": "Zephyr",
        "espidf": "ESP-IDF",
        "picosdk": "Pico SDK",
        "unit": "Linux GCC",
    }
    for cat_dir in sorted((ROOT / "cpp/tests").glob("*")):
        if not cat_dir.is_dir():
            continue
        for chip_dir in cat_dir.glob("*"):
            if not chip_dir.is_dir():
                continue
            m = re.match(r"^(.*)_test(?:_(\w+))?$", chip_dir.name)
            if not m:
                continue
            chip_name, suffix = m.group(1), m.group(2)
            category, chip = lookup_chip(chip_name)
            platform = test_platform_by_suffix.get(suffix, "Arduino")
            for f in iter_files(chip_dir, {"h", "hpp", "cpp", "cc", "ino"}):
                add(lang, platform, category, chip, "tests", count_lines(f))


# --- Node.js / Node-RED ----------------------------------------------------

def gather_nodejs():
    lang = "Node.js"
    lang_nr = "Node-RED"

    for cat_dir in sorted((ROOT / "nodejs/packages/periph/src/chips").glob("*")):
        if not cat_dir.is_dir():
            continue
        for f in iter_files(cat_dir, {"js"}):
            category, chip = lookup_chip(f.stem)
            add(lang, None, category, chip, "source", count_lines(f))

    for f in iter_files(ROOT / "nodejs/packages/periph/src/connection", {"js"}):
        add(lang, None, None, None, "source", count_lines(f))

    # Package root (index.js)
    for f in (ROOT / "nodejs/packages/periph/src").glob("*.js"):
        add(lang, None, None, None, "source", count_lines(f))

    for chip_dir in (ROOT / "nodejs/packages/periph/examples").glob("*/*"):
        if not chip_dir.is_dir():
            continue
        category, chip = lookup_chip(chip_dir.name)
        for f in iter_files(chip_dir, {"js"}):
            add(lang, None, category, chip, "examples", count_lines(f))

    for cat_dir in sorted((ROOT / "nodejs/tests").glob("*")):
        if not cat_dir.is_dir():
            continue
        for f in iter_files(cat_dir, {"js"}):
            m = re.match(r"^(.*)_test(?:_(\w+))?$", f.stem)
            if not m:
                continue
            category, chip = lookup_chip(m.group(1))
            add(lang, None, category, chip, "tests", count_lines(f))

    # Node-RED node packages
    for pkg_dir in sorted((ROOT / "nodejs/packages").glob("node-red-contrib-periph-*")):
        nodes_dir = pkg_dir / "nodes"
        for chip_dir in nodes_dir.glob("*"):
            if not chip_dir.is_dir():
                continue
            category, chip = lookup_chip(chip_dir.name)
            for f in iter_files(chip_dir, {"js", "html"}):
                add(lang_nr, "Node-RED", category, chip, "source", count_lines(f))
        examples_dir = pkg_dir / "examples"
        for chip_dir in examples_dir.glob("*"):
            if not chip_dir.is_dir():
                continue
            category, chip = lookup_chip(chip_dir.name)
            for f in iter_files(chip_dir, {"json"}):
                add(lang_nr, "Node-RED", category, chip, "examples", count_lines(f))


# --- Rust ------------------------------------------------------------------

def gather_rust():
    lang = "Rust"

    for cat_dir in sorted((ROOT / "rust/periph/src/chips").glob("*")):
        if not cat_dir.is_dir():
            continue
        for f in iter_files(cat_dir, {"rs"}):
            category, chip = lookup_chip(f.stem)
            add(lang, None, category, chip, "source", count_lines(f))

    for f in iter_files(ROOT / "rust/periph/src/connection", {"rs"}):
        platform = "Linux" if f.stem.endswith("_linux") else "Generic (embedded-hal)"
        add(lang, platform, None, None, "source", count_lines(f))

    # Crate root (lib.rs)
    for f in (ROOT / "rust/periph/src").glob("*.rs"):
        add(lang, None, None, None, "source", count_lines(f))

    example_platform = {"linux": "Linux", "esp32s3": "ESP32-S3"}
    for base, platform in (("rust/examples/linux", "Linux"), ("rust/examples/embedded/esp32s3", "ESP32-S3")):
        for chip_dir in (ROOT / base).glob("*/*"):
            if not chip_dir.is_dir():
                continue
            category, chip = lookup_chip(chip_dir.name)
            for f in iter_files(chip_dir, {"rs"}):
                add(lang, platform, category, chip, "examples", count_lines(f))

    for cat_dir in sorted((ROOT / "rust/tests").glob("*")):
        if not cat_dir.is_dir():
            continue
        for chip_dir in cat_dir.glob("*"):
            if not chip_dir.is_dir():
                continue
            if chip_dir.name.endswith("_test_esp32s3"):
                chip_name, platform = chip_dir.name[: -len("_test_esp32s3")], "ESP32-S3"
            elif chip_dir.name.endswith("_test"):
                chip_name, platform = chip_dir.name[: -len("_test")], "Linux"
            else:
                continue
            category, chip = lookup_chip(chip_name)
            for f in iter_files(chip_dir, {"rs"}):
                add(lang, platform, category, chip, "tests", count_lines(f))


# --- Go ----------------------------------------------------------------------

def gather_go():
    lang = "Go"

    for cat_dir in sorted((ROOT / "go/periph/chips").glob("*")):
        if not cat_dir.is_dir():
            continue
        for f in iter_files(cat_dir, {"go"}):
            if f.stem.endswith("_test"):
                # in-package unit test, colocated with the driver
                category, chip = lookup_chip(f.stem[: -len("_test")])
                add(lang, "Linux", category, chip, "tests", count_lines(f))
                continue
            category, chip = lookup_chip(f.stem)
            add(lang, None, category, chip, "source", count_lines(f))

    for f in iter_files(ROOT / "go/periph/connection", {"go"}):
        if f.stem.endswith("_linux"):
            platform = "Linux"
        elif f.stem.endswith("_tinygo"):
            platform = "TinyGo"
        else:
            platform = "Generic"
        add(lang, platform, None, None, "source", count_lines(f))

    # Module root (doc.go)
    for f in (ROOT / "go/periph").glob("*.go"):
        add(lang, None, None, None, "source", count_lines(f))

    for base, platform in (("go/examples/linux", "Linux"), ("go/examples/tinygo", "TinyGo")):
        for chip_dir in (ROOT / base).glob("*/*"):
            if not chip_dir.is_dir():
                continue
            category, chip = lookup_chip(chip_dir.name)
            for f in iter_files(chip_dir, {"go"}):
                add(lang, platform, category, chip, "examples", count_lines(f))

    for cat_dir in sorted((ROOT / "go/tests").glob("*")):
        if not cat_dir.is_dir():
            continue
        for chip_dir in cat_dir.glob("*"):
            if not chip_dir.is_dir():
                continue
            if chip_dir.name.endswith("_test_tinygo"):
                chip_name, platform = chip_dir.name[: -len("_test_tinygo")], "TinyGo"
            elif chip_dir.name.endswith("_test"):
                chip_name, platform = chip_dir.name[: -len("_test")], "Linux"
            else:
                continue
            category, chip = lookup_chip(chip_name)
            for f in iter_files(chip_dir, {"go"}):
                add(lang, platform, category, chip, "tests", count_lines(f))


# --- JVM (Java / Kotlin / Groovy) ------------------------------------------

def gather_jvm():
    lang = "JVM"
    module_platform = {"periph-java": "Java", "periph-kotlin": "Kotlin", "periph-groovy": "Groovy"}
    module_ext = {"periph-java": {"java"}, "periph-kotlin": {"kt"}, "periph-groovy": {"groovy"}}

    for module, platform in module_platform.items():
        exts = module_ext[module]
        main_root = ROOT / f"jvm/{module}/src/main"
        for lang_dir in main_root.glob("*"):  # java / kotlin / groovy
            for cat_dir in lang_dir.glob("it/uhde/periph/chips/*"):
                if not cat_dir.is_dir():
                    continue
                category = cat_dir.name
                for f in iter_files(cat_dir, exts):
                    stem = re.sub(r"(Minimal|Full)$", "", f.stem)
                    c2, chip = lookup_chip(stem)
                    add(lang, platform, c2 or category, chip, "source", count_lines(f))

        test_root = ROOT / f"jvm/{module}/src/test"
        for lang_dir in test_root.glob("*"):
            for cat_dir in lang_dir.glob("it/uhde/periph/chips/*"):
                if not cat_dir.is_dir():
                    continue
                category = cat_dir.name
                for f in iter_files(cat_dir, exts):
                    stem = re.sub(r"(Test|Spec)$", "", f.stem)
                    c2, chip = lookup_chip(stem)
                    add(lang, platform, c2 or category, chip, "tests", count_lines(f))

        examples_root = ROOT / f"jvm/examples/{platform.lower()}"
        for chip_dir in examples_root.glob("*/*"):
            if not chip_dir.is_dir():
                continue
            category, chip = lookup_chip(chip_dir.name)
            for f in iter_files(chip_dir, exts):
                add(lang, platform, category, chip, "examples", count_lines(f))

    # module-info.java (JPMS root, periph-java only -- periph-kotlin/groovy have none)
    module_info = ROOT / "jvm/periph-java/src/main/java/module-info.java"
    if module_info.exists():
        add(lang, "Java", None, None, "source", count_lines(module_info))

    # Shared connection library (used by all three)
    for f in iter_files(ROOT / "jvm/periph-connection/src/main/java", {"java"}):
        add(lang, "Linux (shared connection)", None, None, "source", count_lines(f))
    for f in iter_files(ROOT / "jvm/periph-connection/src/test/java", {"java"}):
        add(lang, "Linux (shared connection)", None, None, "tests", count_lines(f))

    # Hardware JBang tests, categorized by file extension
    ext_platform = {"java": "Java", "kt": "Kotlin", "groovy": "Groovy"}
    for cat_dir in sorted((ROOT / "jvm/tests").glob("*")):
        if not cat_dir.is_dir():
            continue
        for chip_dir in cat_dir.glob("*"):
            if not chip_dir.is_dir():
                continue
            category, chip = lookup_chip(chip_dir.name)
            for ext, platform in ext_platform.items():
                for f in iter_files(chip_dir, {ext}):
                    add(lang, platform, category, chip, "tests", count_lines(f))


# --- Sigrok ------------------------------------------------------------------

def gather_sigrok():
    lang = "Sigrok"
    sigrok_root = ROOT / "sigrok"
    skip = {"tests", "CONFIGS.example", "PROBING.md"}
    for entry in sorted(sigrok_root.glob("*")):
        if entry.name in skip or not entry.is_dir():
            continue
        if entry.name == "gyroscope":
            # l3g4200d is nested here instead of flat under sigrok/
            for chip_dir in entry.glob("*"):
                category, chip = lookup_chip(chip_dir.name)
                for f in iter_files(chip_dir, {"py"}):
                    add(lang, None, category, chip, "source", count_lines(f))
            continue
        category, chip = lookup_chip(entry.name)
        for f in iter_files(entry, {"py"}):
            add(lang, None, category, chip, "source", count_lines(f))


# --- Run all gatherers -------------------------------------------------------

def gather_all():
    gather_python()
    gather_cpp()
    gather_nodejs()
    gather_rust()
    gather_go()
    gather_jvm()
    gather_sigrok()


# --- Aggregation ---------------------------------------------------------

def aggregate(records):
    lang_totals = defaultdict(lambda: defaultdict(int))
    platform_totals = defaultdict(lambda: defaultdict(int))  # (language, platform) -> scope -> lines
    category_totals = defaultdict(lambda: defaultdict(int))
    chip_totals = defaultdict(lambda: defaultdict(int))
    chip_category = {}

    for r in records:
        lang_totals[r["language"]][r["scope"]] += r["lines"]
        lang_totals[r["language"]]["total"] += r["lines"]

        if r["platform"]:
            key = (r["language"], r["platform"])
            platform_totals[key][r["scope"]] += r["lines"]
            platform_totals[key]["total"] += r["lines"]

        if r["category"]:
            category_totals[r["category"]][r["scope"]] += r["lines"]
            category_totals[r["category"]]["total"] += r["lines"]

        if r["chip"]:
            chip_totals[r["chip"]][r["scope"]] += r["lines"]
            chip_totals[r["chip"]]["total"] += r["lines"]
            chip_category[r["chip"]] = r["category"]

    return lang_totals, platform_totals, category_totals, chip_totals, chip_category


# --- Markdown rendering ----------------------------------------------------

def fmt(n):
    return f"{n:,}"


def render(records):
    lang_totals, platform_totals, category_totals, chip_totals, chip_category = aggregate(records)

    global_totals = defaultdict(int)
    for scope_totals in lang_totals.values():
        for scope in SCOPES:
            global_totals[scope] += scope_totals[scope]
        global_totals["total"] += scope_totals["total"]

    lines = []
    lines.append("# Repository Statistics")
    lines.append("")
    lines.append(
        "Non-blank line counts, regenerated by [`scripts/generate_stats.py`](scripts/generate_stats.py). "
        "Run it manually after a significant source change:"
    )
    lines.append("")
    lines.append("```sh")
    lines.append("python3 scripts/generate_stats.py")
    lines.append("```")
    lines.append("")
    lines.append(
        "`release.sh` also regenerates this file automatically as part of stamping a release, so it's "
        "always current as of the last tagged version even if nobody ran it by hand in between."
    )
    lines.append("")
    lines.append(
        "Chip driver code is shared across a language's platforms (e.g. C++'s `chips/` is identical "
        "whether the target is Arduino or Zephyr), so it's counted once under that language's totals, "
        "not once per platform. Connection/transport code, examples, and tests genuinely differ per "
        "platform and are broken out that way below. JVM is the exception: Java, Kotlin, and Groovy are "
        "three independent re-implementations, not a shared driver, so JVM's platform axis carries its "
        "own driver line counts too."
    )
    lines.append("")

    # Global
    lines.append("## Global")
    lines.append("")
    lines.append("| Scope | Lines |")
    lines.append("|---|---:|")
    lines.append(f"| Source (drivers + connection + blocks/nodes/decoders) | {fmt(global_totals['source'])} |")
    lines.append(f"| Tests | {fmt(global_totals['tests'])} |")
    lines.append(f"| Examples | {fmt(global_totals['examples'])} |")
    lines.append(f"| **Total** | **{fmt(global_totals['total'])}** |")
    lines.append("")

    # By language
    lines.append("## By language")
    lines.append("")
    lines.append("| Language | Source | Tests | Examples | Total |")
    lines.append("|---|---:|---:|---:|---:|")
    for lang in sorted(lang_totals, key=lambda l: -lang_totals[l]["total"]):
        t = lang_totals[lang]
        lines.append(f"| {lang} | {fmt(t['source'])} | {fmt(t['tests'])} | {fmt(t['examples'])} | **{fmt(t['total'])}** |")
    lines.append(f"| **Total** | {fmt(global_totals['source'])} | {fmt(global_totals['tests'])} | {fmt(global_totals['examples'])} | **{fmt(global_totals['total'])}** |")
    lines.append("")

    # By platform
    lines.append("## By platform")
    lines.append("")
    lines.append(
        "Only counts what's actually platform-specific (connection/transport code, block/node wrappers, "
        "examples, tests) — see the note above about shared driver code. JVM's Java/Kotlin/Groovy rows are "
        "the exception and include their own full driver source."
    )
    lines.append("")
    lines.append("| Language | Platform | Source | Tests | Examples | Total |")
    lines.append("|---|---|---:|---:|---:|---:|")
    for lang, platform in sorted(platform_totals, key=lambda k: (k[0], -platform_totals[k]["total"])):
        t = platform_totals[(lang, platform)]
        lines.append(f"| {lang} | {platform} | {fmt(t['source'])} | {fmt(t['tests'])} | {fmt(t['examples'])} | **{fmt(t['total'])}** |")
    lines.append("")

    # By category
    lines.append("## By category")
    lines.append("")
    lines.append(
        "Totals here are lower than the Global/By-language source figures because generic "
        "connection/transport code (shared across every chip in a language, not tied to one "
        "category or chip) is excluded from this table and the one below."
    )
    lines.append("")
    lines.append("| Category | Chips | Source | Tests | Examples | Total |")
    lines.append("|---|---:|---:|---:|---:|---:|")
    chips_per_category = defaultdict(set)
    for chip, cat in chip_category.items():
        chips_per_category[cat].add(chip)
    cat_total = defaultdict(int)
    for cat in sorted(category_totals, key=lambda c: -category_totals[c]["total"]):
        t = category_totals[cat]
        lines.append(f"| `{cat}` | {len(chips_per_category[cat])} | {fmt(t['source'])} | {fmt(t['tests'])} | {fmt(t['examples'])} | **{fmt(t['total'])}** |")
        for scope in SCOPES:
            cat_total[scope] += t[scope]
        cat_total["total"] += t["total"]
    lines.append(f"| **Total** | {len(chip_category)} | {fmt(cat_total['source'])} | {fmt(cat_total['tests'])} | {fmt(cat_total['examples'])} | **{fmt(cat_total['total'])}** |")
    lines.append("")

    # By chip
    lines.append("## By chip")
    lines.append("")
    lines.append("| Chip | Category | Source | Tests | Examples | Total |")
    lines.append("|---|---|---:|---:|---:|---:|")
    for chip in sorted(chip_totals, key=lambda c: (chip_category.get(c, ""), c)):
        t = chip_totals[chip]
        cat = chip_category.get(chip, "")
        # `chip` here is already the display name returned by lookup_chip()
        lines.append(f"| {chip} | `{cat}` | {fmt(t['source'])} | {fmt(t['tests'])} | {fmt(t['examples'])} | **{fmt(t['total'])}** |")
    lines.append("")

    return "\n".join(lines) + "\n"


def main():
    gather_all()
    if not records:
        print("error: no source files counted -- check ROOT / directory layout", file=sys.stderr)
        sys.exit(1)
    content = render(records)
    (ROOT / "STATS.md").write_text(content)
    print(f"wrote {ROOT / 'STATS.md'} ({len(records)} files counted)")


if __name__ == "__main__":
    main()
