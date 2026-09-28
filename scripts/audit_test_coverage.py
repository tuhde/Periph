#!/usr/bin/env python3
"""Cross-language unit-test coverage audit for every chip spec.

Usage:
    python3 scripts/audit_test_coverage.py

For each chip spec under specs/<category>/<chip>.md (excluding _template_*
and shared _*_base.md family specs), checks whether a driver and a matching
unit test exist in each of the eight language targets (cpp, python, nodejs,
rust, go, jvm-java, jvm-kotlin, jvm-groovy), and prints a coverage matrix
plus a flat "missing test, driver exists" list per language -- the to-do
list for backfilling specs/testing_framework.md coverage (issue #73;
backfill was explicitly scoped out of #73 itself, see its "Rollout Scope").

A "no driver" cell means the language has no driver for that chip at all --
that's a driver-porting gap, not a test gap, and does not belong on the
test-coverage to-do list. Re-run after each backfill PR instead of manually
re-deriving the matrix.
"""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

LANGS = ["cpp", "python", "nodejs", "rust", "go", "jvm-java", "jvm-kotlin", "jvm-groovy"]

# chip_slug (as it appears in specs/<category>/<chip>.md) -> alternate
# basename stems to also try, for chips whose driver/test filenames don't
# normalize 1:1 from the spec slug (e.g. a family split into several parts).
ALIASES = {
    "neo-6": ["neo6"],
    "apds-9930": ["apds9930"],
    "rfm9x": ["rfm95", "rfm96", "rfm97", "rfm98", "rfm9x"],
}


def norm(s: str) -> str:
    return re.sub(r"[_-]", "", s.lower())


def candidates(chip_slug: str) -> list[str]:
    return [norm(c) for c in [chip_slug, *ALIASES.get(chip_slug, [])]]


def chip_specs() -> list[tuple[str, str]]:
    chips = []
    for md in sorted((ROOT / "specs").glob("*/*.md")):
        name = md.stem
        if name.startswith("_"):
            continue
        chips.append((md.parent.name, name))
    return chips


def any_file_startswith(directory: Path, chip: str, suffix: str,
                         strip_suffixes: tuple[str, ...] = ()) -> bool:
    """True if `directory` (non-recursive) has a file whose name, with
    `suffix` and any of `strip_suffixes` removed, starts with a normalized
    form of `chip` (or one of its ALIASES)."""
    if not directory.is_dir():
        return False
    cands = candidates(chip)
    for f in directory.iterdir():
        if not f.is_file() or f.suffix != suffix:
            continue
        stem = f.stem
        for strip in strip_suffixes:
            if stem.endswith(strip):
                stem = stem[: -len(strip)]
                break
        stem_n = norm(stem)
        if any(stem_n.startswith(c) for c in cands):
            return True
    return False


def any_dir_startswith(directory: Path, chip: str, suffix: str) -> bool:
    if not directory.is_dir():
        return False
    cands = candidates(chip)
    for d in directory.iterdir():
        if not d.is_dir() or not d.name.endswith(suffix):
            continue
        stem_n = norm(d.name[: -len(suffix)])
        if any(stem_n.startswith(c) for c in cands):
            return True
    return False


def suffixed_file_startswith(directory: Path, chip: str, full_suffix: str) -> bool:
    """True if `directory` has a file ending in `full_suffix` (e.g.
    "_test_unit.py") whose remaining stem starts with a normalized form of
    `chip` (or one of its ALIASES)."""
    if not directory.is_dir():
        return False
    cands = candidates(chip)
    for f in directory.iterdir():
        if not f.is_file() or not f.name.endswith(full_suffix):
            continue
        stem_n = norm(f.name[: -len(full_suffix)])
        if any(stem_n.startswith(c) for c in cands):
            return True
    return False


def driver_exists(category: str, chip: str) -> dict:
    return {
        "cpp": any_file_startswith(ROOT / f"cpp/src/chips/{category}", chip, ".h"),
        "python": any_file_startswith(ROOT / f"python/periph/chips/{category}", chip, ".py"),
        "nodejs": any_file_startswith(ROOT / f"nodejs/packages/periph/src/chips/{category}", chip, ".js"),
        "rust": any_file_startswith(ROOT / f"rust/periph/src/chips/{category}", chip, ".rs"),
        "go": any_file_startswith(ROOT / f"go/periph/chips/{category}", chip, ".go", strip_suffixes=("_test",)),
        "jvm-java": any_file_startswith(
            ROOT / f"jvm/periph-java/src/main/java/it/uhde/periph/chips/{category}", chip, ".java",
            strip_suffixes=("Full", "Minimal")),
        "jvm-kotlin": any_file_startswith(
            ROOT / f"jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/{category}", chip, ".kt",
            strip_suffixes=("Full", "Minimal")),
        "jvm-groovy": any_file_startswith(
            ROOT / f"jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/{category}", chip, ".groovy",
            strip_suffixes=("Full", "Minimal")),
    }


def test_exists(category: str, chip: str) -> dict:
    return {
        "cpp": any_dir_startswith(ROOT / f"cpp/tests/{category}", chip, "_test_unit"),
        "python": suffixed_file_startswith(ROOT / f"python/tests/{category}", chip, "_test_unit.py"),
        "nodejs": suffixed_file_startswith(ROOT / f"nodejs/tests/{category}", chip, "_test_unit.js"),
        "rust": rust_unit_test_exists(category, chip),
        "go": suffixed_file_startswith(ROOT / f"go/periph/chips/{category}", chip, "_test.go"),
        "jvm-java": jvm_test_exists("java", "java", "Test", category, chip),
        "jvm-kotlin": jvm_test_exists("kotlin", "kt", "Test", category, chip),
        "jvm-groovy": jvm_test_exists("groovy", "groovy", "Spec", category, chip),
    }


def rust_unit_test_exists(category: str, chip: str) -> bool:
    d = ROOT / f"rust/periph/src/chips/{category}"
    if not d.is_dir():
        return False
    for f in d.glob("*.rs"):
        if f.stem == "mod":
            continue
        if any(norm(f.stem).startswith(c) for c in candidates(chip)):
            if "#[cfg(test)]" in f.read_text(errors="ignore"):
                return True
    return False


def jvm_test_exists(lang_dir: str, ext: str, suffix: str, category: str, chip: str) -> bool:
    d = ROOT / f"jvm/periph-{lang_dir}/src/test/{lang_dir}/it/uhde/periph/chips/{category}"
    if not d.is_dir():
        return False
    for f in d.glob(f"*.{ext}"):
        if not f.stem.endswith(suffix):
            continue
        stem_n = norm(f.stem[: -len(suffix)])
        if any(stem_n.startswith(c) for c in candidates(chip)):
            return True
    return False


def main():
    rows = []
    gaps = {lang: [] for lang in LANGS}
    family_bases = []

    for md in sorted((ROOT / "specs").glob("*/*.md")):
        if md.stem.startswith("_"):
            family_bases.append(f"{md.parent.name}/{md.stem}")

    for category, chip in chip_specs():
        drivers = driver_exists(category, chip)
        tests = test_exists(category, chip)
        row = [f"{category}/{chip}"]
        for lang in LANGS:
            if not drivers[lang]:
                row.append("no driver")
            elif tests[lang]:
                row.append("OK")
            else:
                row.append("MISSING")
                gaps[lang].append(f"{category}/{chip}")
        rows.append(row)

    print("Family-base specs (skipped, not standalone chips):")
    for fb in family_bases:
        print(f"  - {fb}")
    print()

    header = ["chip"] + LANGS
    print("| " + " | ".join(header) + " |")
    print("|" + "---|" * len(header))
    for row in rows:
        print("| " + " | ".join(row) + " |")

    print()
    print("## Missing test, driver exists (per language) -- the backfill to-do list")
    total_gaps = 0
    for lang in LANGS:
        print(f"\n### {lang} ({len(gaps[lang])})")
        total_gaps += len(gaps[lang])
        for c in gaps[lang]:
            print(f"- {c}")
    print(f"\nTotal gaps across all languages: {total_gaps}")


if __name__ == "__main__":
    main()
