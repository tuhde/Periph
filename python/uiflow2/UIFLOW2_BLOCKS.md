# UIFlow 2 native custom blocks (`.m5b2`) — reference for this repo

This document specifies the `.m5b2` format used by the current M5Stack UIFlow 2 web IDE
(uiflow2.m5stack.com) and how a `.m5b2` should be generated for a Periph chip. It is a
design reference for the `feature/uiflow2` effort — **nothing described here is
implemented in this repo yet.** No `m5b2gen.py`, no wrapper classes, no `.m5b2`
output exist at the time of writing; this directory currently holds only this document.

> **Format status:** `.m5b2` carries the internal version tag `"alpha2"`. M5Stack has not
> published a spec for it. Everything below was reverse-engineered from a UiFlow2-exported
> file and cross-checked with a verified round-trip. Treat it as liable to break on a
> UiFlow2 update — see [Verification](#6-verification).

---

## 0. Why this is a separate directory from `python/uiflow1/`

This repo used to have a single `python/uiflow/` directory that the root
[README](../../README.md), [CLAUDE.md](../../CLAUDE.md), and 27 shipped chips called
"UIFlow 2 custom blocks." Its `generate.sh` produces `<chip>.m5b` files via the community
[`uiflow-custom-block-generator`](https://github.com/3110/uiflow-custom-block-generator)
(the "3110" tool).

The reverse-engineering below showed that `.m5b` and the 3110 tool actually belong to
**UiFlow 1**, the predecessor app — not the current UiFlow 2 web IDE, whose native
custom-block format is `.m5b2`: a different container, a different authoring convention
(one annotated Python class per chip instead of a JSON manifest plus one file per block),
and no official generator. The formats are incompatible and there is no `.m5b` → `.m5b2`
converter, so this can't be folded into the existing tree as a variant — it's a distinct
platform.

That directory was renamed to `python/uiflow1/` and this `python/uiflow2/` directory was
created alongside it to hold true UiFlow 2 (`.m5b2`) support once implemented. Whether
UiFlow 2's Extension → Import still also accepts a legacy `.m5b` (so the 27 chips already
in `python/uiflow1/` might still work there too, coincidentally) is unverified — see
[Open items](#8-open-items). That's a compatibility question, not a reason to merge the
two trees: even if `.m5b` still imports, `.m5b2` is the format the app is actually built
around now.

---

## 1. UiFlow 1 vs. UiFlow 2

| | UiFlow 1 | UiFlow 2 |
|---|---|---|
| Block file | `.m5b` | `.m5b2` |
| Project file | `.m5f` | `.m5f2` |
| Container | ZIP-like | plain JSON |
| Block definition | JSON manifest + one `.py` file per block | **one Python class with YAML docstrings** |
| Block type | explicit (`"type": "value"` / `"execute"`) | **derived from the return annotation** |
| Generator tool | `uiflow-custom-block-generator` (3110) — what `python/uiflow1/generate.sh` uses today | none official → would be a new `python/uiflow2/m5b2gen.py` in this repo |

The formats are **not** compatible and there is no official `.m5b` → `.m5b2` converter.
The existing `python/uiflow1/<category>/<chip>/*.py` block files cannot be reused as-is for
`.m5b2` — for UiFlow 2, each chip needs one annotated Python class instead.

---

## 2. Anatomy of a `.m5b2` file

Plain JSON, UTF-8, five top-level keys plus `version`:

```
{
  "category": "<ClassName>",
  "color":    "#a6bbcf",
  "uiflow2":  { jscode, toolbox, toolboxitemid, block_type },
  "data":     { name, note, details, header, assignments, example,
                source_internal, source_external, members, python_file_name },
  "pyCode":   "<complete Python source as a string>",
  "version":  "alpha2"
}
```

(`#a6bbcf` above is Periph's existing block color, matching the palette already used for
`python/uiflow1/` and the Node-RED nodes — reuse it for `.m5b2` blocks too, per
`python/uiflow1/README.md`.)

**Key fact: `pyCode` is the single source of truth.** `data` is the parsed AST
representation of it; `uiflow2` is the Blockly code generated from it. If `pyCode`
changes, `data` and `uiflow2` must be regenerated — UiFlow 2 does not re-derive them at
runtime.

**Exception — the header block is re-rendered.** The code shown in the Block Designer is
byte-identical to the stored `pyCode` except for one field: the `time` entry in the module
docstring carries the **current** date, while `data.header.time` keeps the **creation**
date. In the reference export, `pyCode` shows `time 2026-08-27` while `data.header.time`
still reads `2024-09-14`.

Consequence: never derive `data.header.time` from `pyCode`. When regenerating an existing
`.m5b2`, pass the original value through explicitly (e.g. a `--created` flag on the future
generator), or the creation date will churn on every rebuild and produce diff noise —
exactly the failure mode `python/uiflow1/generate.sh` was made deterministic to avoid for
`.m5b` (see its header comment).

### 2.1 `pyCode` — the source

A Python class whose docstrings contain YAML:

```python
"""
file     ExampleClass
time     2024-09-14
author
email
license  MIT License
"""


class ExampleClass:
    """
    note:
        en: ''
    details:
        color: '#a6bbcf'
        link: https://github.com/tuhde/Periph
        image: ''
        category: Custom
    example: ''
    """

    def __init__(self):
        """
        label:
            en: '%1 init'
        """
        pass

    def method1(self, param0: str, option):
        """
        label:
            en: method %1 param0 %2 option %3
        params:
            param0:
                name: param0
                type: str
            option:
                name: option
                field: dropdown
                options:
                    option1: '0'
                    option2: '1'
                    option3: '2'
        """
        pass

    def method2(self, param1: str, param2: int = 0) -> None:
        """
        label:
            en: method %1 param1 %2 param2 %3
        params:
            param1:
                name: param1
                type: str
            param2:
                name: param2
                type: int
                default: '0'
                field: number
                max: '100'
                min: '0'
        """
        pass
```

Rules for `label`:

- `%1` is **always** the instance field (the object instance's variable name).
- `%2`, `%3`, … are the parameters in signature order.
- The text can be localized (`en:`, plus any other language keys).

**This class is not the Periph driver.** Periph's `python/periph/chips/<category>/<chip>.py`
classes (`<Chip>Minimal` / `<Chip>Full`) have no return-type annotations — see e.g.
`python/periph/chips/power/ina219.py`, where `voltage(self):` returns a float but is not
annotated `-> float`. Since block type in `.m5b2` is derived purely from the presence and
value of a return annotation (see [§3.1](#31-the-return-annotation-decides-the-block-type)),
feeding a driver file straight into the generator would misclassify every getter as a
statement block. The `.m5b2` source must be a thin wrapper class — analogous to the
`<chip>_init.py` / `<chip>_read_<value>.py` block files already written by hand for the
`.m5b` blocks — that calls into the `Full` class and adds the annotations UiFlow 2 needs.

### 2.2 `data.members` — per method

```json
{
  "name": "method2",
  "note": {},
  "label": { "en": "method %1 param1 %2 param2 %3" },
  "params": [ { "name", "type", "default", "note", "field", "max", "min", "options" } ],
  "return": "",
  "source": "        pass",
  "ast_return": { "code": null, "id": "None" },
  "doc_return": null
}
```

`source` holds the method body indented 8 spaces, with the docstring stripped out.

### 2.3 `uiflow2.jscode` — generated Blockly code

Per class, this contains:

1. `const CUSTOM_<UPPER>_LANGUAGES` — label text, keyed `CUSTOM_<CLASS>_<METHOD>` (uppercase).
2. `Blockly.BlockRegExpList['custom_<lower>']` with `code: "from <Class> import <Class>"` —
   the import line UiFlow 2 writes into the generated MicroPython.
3. `Blockly.Msg.CUSTOM_<UPPER>_HUE` and `..._<UPPER>` — color and display name.
4. `Blockly.utils.getcustom_<lower>Options` — dropdown of existing instances.
5. One `Blockly.Blocks[...]` and one `Blockly.Python[...]` pair per method.

### 2.4 `uiflow2.toolbox` — XML fragment

```xml
<category name="X" colour="#..." hidden="true" toolboxitemid="custom_x">
<title text="X" docsLink="..."></title>
<block type="custom_x_init"/><block type="custom_x_method1">
  <value name="param0">
    <shadow type="text"><field name="TEXT"/></shadow>
  </value>
</block>
</category>
```

---

## 3. Three rules you have to know

### 3.1 The return annotation decides the block type

| Signature | `ast_return.id` | Blockly | Python generator |
|---|---|---|---|
| `def m(self)` | `null` | `previousStatement` + `nextStatement` | `` return `${varname}.m()\n` `` |
| `def m(self) -> None` | `"None"` | `'output': null` | `return [..., ORDER_NONE]` |
| `def m(self) -> int` | `"int"` | `'output': null` | `return [..., ORDER_NONE]` |

**Merely having a return annotation makes the block a value block — even `-> None`.**
This looks like a UiFlow 2 bug, but it's the observed behavior and must be reproduced.

Practical consequence for Periph wrapper methods: methods with no return value (`set_mode`,
`write`, `reset`) get **no** annotation. Methods that return something (`read_temperature`,
`is_data_ready`) get the real annotation (`-> float`, `-> int`, `-> bool`) even though the
underlying `Full`-class method they call is unannotated.

### 3.2 File name and class name must match exactly

The generated JS hard-codes `from <Class> import <Class>`. The wrapper file must therefore
be deployed to the device as `<ClassName>.py`. `data.python_file_name` additionally stores
the lowercase form — both fields must agree.

### 3.3 A known inconsistency in `block_type`

`uiflow2.block_type` lists the constructor as `custom_<lower>___init__` (three
underscores), while `jscode` registers it as `custom_<lower>_init`. This is how the
original does it and any generator should reproduce it deliberately. **Do not "fix" it.**

---

## 4. Parameter fields

| YAML | Blockly | Toolbox shadow |
|---|---|---|
| `type: str` (no `field`) | `input_value` | `<shadow type="text">` |
| `type: int` / `float`, `field: number` | `input_value` | `<shadow type="math_number">` with `default` as `NUM` |
| `field: dropdown` + `options:` | `field_dropdown` inline | no shadow |
| — (`__init__` only) | `field_input` defaulting to `<lower>_0` | — |

`min`/`max` are carried through in `data.params`. Other field types (boolean, color, angle)
are **unverified** — if a chip needs one, build a test block in the UiFlow 2 web IDE first,
export it, and add the mapping.

---

## 5. Workflow: adding a chip's `.m5b2` (once `feature/uiflow2` lands the generator)

### Step 1 — write the wrapper class

One class per chip, class name == file name, wrapping the chip's `Full` class from
`python/periph/chips/<category>/<chip>.py` (mirroring how `python/uiflow1/<category>/<chip>/
<chip>_init.py` already wraps the `periph.connection.<transport>_auto` factory for the
`.m5b` blocks). Methods prefixed with `_` (other than `__init__`) are ignored and produce
no blocks, so internals stay hidden.

The body can hold real code — for a pure block definition, `pass` is enough, but a
runnable body means the same file can be dropped on a device and imported directly.

### Step 2 — add YAML docstrings

Class docstring: `note`, `details` (with `color`, `link`, `image`, `category`), `example`.
Method docstrings: `label` and `params` as in [§2.1](#21-pycode--the-source).

Keep the color consistent per chip family — Periph already standardizes on `#a6bbcf` for
its `.m5b` blocks; reuse it here unless there's a reason to diverge per category.

### Step 3 — generate

```bash
# would follow the same self-managed-venv pattern as python/uiflow1/generate.sh once
# python/uiflow2/m5b2gen.py exists:
python3 python/uiflow2/m5b2gen.py <Chip>.py -o <category>/<chip>/<Chip>.m5b2

# rebuilding an existing chip: pass the original creation date through
python3 python/uiflow2/m5b2gen.py <Chip>.py -o <category>/<chip>/<Chip>.m5b2 --created 2024-09-14
```

The generator should derive:
- type hints and defaults from the signature (YAML overrides on conflict)
- block type from the return annotation
- `header` fields (`author`, `email`, `license`) from the module docstring
- `time` from `--created`, else the current date

Empty header fields in the module docstring (`author` followed by whitespace and a
newline) should stay empty. This is deliberate: an earlier version of a similar generator
matched `\s+` across the newline and swallowed the next line as the value. When writing
the header regex, use `[ \t]`, not `\s`.

### Step 4 — verify

See [Verification](#6-verification).

### Step 5 — test in the web IDE

Import the `.m5b2` into UiFlow 2, drag an init block plus one block per method onto the
canvas, switch to the code view, and confirm the generated MicroPython is correct. Then
deploy the wrapper (and the `periph` package) to a device and actually run it.

---

## 6. Verification

The format is undocumented, so every generator change needs:

### Round-trip against a reference file

A committed, unmodified UiFlow 2 export (e.g. `python/uiflow2/tests/fixtures/ExampleClass.m5b2`) serves as
the gold standard. Extract its `pyCode`, run it back through the generator, and compare:

```bash
python3 - <<'EOF'
import json, subprocess, pathlib
ref = json.load(open('python/uiflow2/tests/fixtures/ExampleClass.m5b2'))
pathlib.Path('/tmp/ExampleClass.py').write_text(ref['pyCode'])
# pass --created the fixture's own value, or header.time comparison fails
subprocess.run(['python3', 'python/uiflow2/m5b2gen.py', '/tmp/ExampleClass.py',
                 '-o', '/tmp/out.m5b2',
                 '--created', ref['data']['header']['time']], check=True)
gen = json.load(open('/tmp/out.m5b2'))

for key in ('category', 'color', 'version'):
    assert ref[key] == gen[key], key
assert ref['data'] == gen['data'], 'data block differs'
for key in ('toolbox', 'toolboxitemid', 'block_type'):
    assert ref['uiflow2'][key] == gen['uiflow2'][key], key
print('OK')
EOF
```

**Expected result:** every assertion passes. The **entire** `data` block (including
`header`, `details`, `members`, `python_file_name`) plus `toolbox`, `toolboxitemid`,
`block_type`, `category`, `color`, and `version` are byte-identical to the original. Only
`jscode` differs cosmetically (see below) — this mirrors how `python/uiflow1/generate.sh`
already treats `.m5b` output as deterministic and diffs it in CI, and a future `.m5b2`
generator should get the same `--check` treatment.

**Known, accepted differences in `jscode`** (cosmetic only, semantically equivalent):

1. UiFlow 2 leaves a commented-out `field_dropdown` line in the init block that a generator
   can omit.
2. Line breaks in `args0`: the original writes `'args0': [{`, a generator may write
   `'args0': [\n        {`.
3. An extra blank line before `return` in the generated Python.

For this reason `jscode` should **not** be byte-compared in tests. If a test flags a
difference there, check first whether it's one of these three before treating it as a bug.

### If UiFlow 2 stops loading the files

1. Build a test block in the current web IDE and export it.
2. Check `version` in the export — anything other than `alpha2` means the format changed.
3. Diff the new export against `python/uiflow2/tests/fixtures/ExampleClass.m5b2` and adjust the generator.
4. Replace the fixture with the new export; keep the old one as `ExampleClass.alpha2.m5b2`.

---

## 7. Reference: generator internals (for whoever writes `python/uiflow2/m5b2gen.py`)

Depends only on `pyyaml` and the standard library — same dependency footprint as
`python/uiflow1/generate.sh`'s generator, so it can follow the same self-managed-venv
pattern (`python/uiflow1/.generator`, pinned to a specific commit rather than a PyPI
release) once written.

| Function | Job |
|---|---|
| `parse_doc_yaml(node)` | fetch a docstring and parse it as YAML |
| `extract_members(cls)` | AST → `data.members`, including defaults and annotations |
| `js_block(...)` | one `Blockly.Blocks` + `Blockly.Python` pair |
| `build_jscode(...)` | the complete `uiflow2.jscode` |
| `build_toolbox(...)` | toolbox XML with shadow blocks |
| `generate(py, out)` | orchestrates all of the above, writes the JSON file |

Extension points for new field types: `js_block()` (Blockly definition) and
`build_toolbox()` (shadow mapping) — both must be changed together.

---

## 8. Open items

Format unknowns, carried over from the reverse-engineering session:

- Boolean, color, and angle field types are unverified.
- Multi-language labels are supported by the format (`label.en`, other keys) but a
  generator would only need to pass them through, not validate them.
- `assignments`, `source_internal`, `source_external` are empty in every known export;
  their purpose is unknown.
- Classes with inheritance, or multiple classes per file, are unsupported — a reference
  generator should fail loudly rather than emit something wrong.
- Whether UiFlow 2 accepts a `.m5b2` with multiple categories in one file is untested.

Periph-specific decisions still open for `feature/uiflow2`:

- **Does UiFlow 2's Extension → Import still accept the legacy `.m5b` files** in
  `python/uiflow1/`? If yes, `.m5b2` may only be worth adding for chips where the legacy
  format visibly fails in the current web IDE. If no, every chip's `.m5b2` is a required,
  non-optional addition alongside its `.m5b`.
- **Layout within `python/uiflow2/`.** Not yet fixed — the natural mirror of
  `python/uiflow1/` would be `python/uiflow2/<category>/<chip>/{<Chip>.py,<Chip>.m5b2}`
  plus a top-level `python/uiflow2/generate.sh` and `m5b2gen.py`, analogous to
  `python/uiflow1/generate.sh`, but confirm against whatever the first implemented chip
  actually needs before treating it as settled.
- **`specs/_template_chip.md`'s `## Implementation Checklist`** now has a `### UIFlow 1`
  section requiring `.m5b` (renamed from the old, misleadingly-labeled `### UIFlow 2`
  section — see [[project_uiflow2_format_gap]]). It does **not** yet require `.m5b2`. Add
  a `### UIFlow 2` section (and update `AGENTS.md` / the wiki's `Adding-a-Chip` page to
  match) once `python/uiflow2/m5b2gen.py` exists and at least one chip has gone through
  this workflow end-to-end — don't mandate a deliverable with no working tooling yet.

---

## 9. Sources

- Reverse engineering of a UiFlow 2 export (`ExampleClass.m5b2`, `version: alpha2`)
- Comparison of the stored `pyCode` against the code the Block Designer displays in its UI:
  byte-identical except for the `time` field (see [§2.1](#21-pycode--the-source)). This
  confirms the YAML-in-docstring convention is exactly the Designer's own input format, not
  merely a serialization of it.
- M5Stack community, thread 6837 "Any examples of tutorial on .m5b2 new custom block
  system?" — an M5Stack staff member confirms return values are declared via type
  annotations (`def func(self) -> int`).
- M5Stack community, thread 6524 — confirms there is no `.m5b` → `.m5b2` converter.
- For UiFlow 1, for comparison: https://github.com/3110/uiflow-custom-block-generator
