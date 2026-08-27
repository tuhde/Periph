# UIFlow 2 native custom blocks (`.m5b2`) — reference for this repo

This document specifies the `.m5b2` format used by the current M5Stack UIFlow 2 web IDE
(uiflow2.m5stack.com) and how a Periph chip's `.m5b2` is authored for this repo. It is the
design reference for the `feature/uiflow2` effort — **nothing described here is
implemented in this repo yet.** No wrapper classes and no `.m5b2` output exist at the time
of writing; this directory currently holds only this document.

> **Format status:** `.m5b2` carries the internal version tag `"alpha2"`. M5Stack has not
> published a spec for it. Everything below was reverse-engineered from a UiFlow 2 export
> and cross-checked with a verified round-trip. Treat it as liable to break on a UiFlow 2
> update — see [Troubleshooting](#6-troubleshooting).

---

## 0. Why this is a separate directory from `python/uiflow1/`, and why both are mandatory

This repo used to have a single `python/uiflow/` directory that the root
[README](../../README.md), [CLAUDE.md](../../CLAUDE.md), and 27 shipped chips called
"UIFlow 2 custom blocks." Its `generate.sh` produces `<chip>.m5b` files via the community
[`uiflow-custom-block-generator`](https://github.com/3110/uiflow-custom-block-generator)
(the "3110" tool).

The reverse-engineering below showed that `.m5b` and the 3110 tool actually belong to
**UiFlow 1**, the predecessor app — not the current UiFlow 2 web IDE, whose native
custom-block format is `.m5b2`: a different container and a different authoring convention
(one annotated Python class per chip instead of a JSON manifest plus one file per block).
The formats are incompatible and there is no `.m5b` → `.m5b2` converter.

That directory was renamed to `python/uiflow1/`, and this `python/uiflow2/` directory holds
true UiFlow 2 (`.m5b2`) support. **Confirmed: UIFlow 1 and UIFlow 2 cannot interact — a
`.m5b` does not work in the UIFlow 2 app and a `.m5b2` does not work in UIFlow 1.** Both are
therefore mandatory, non-optional deliverables for every chip, not alternatives to choose
between — see the `### UIFlow 2` section added to `specs/_template_chip.md` and
`specs/_template_chip_io_expander.md`.

---

## 1. UiFlow 1 vs. UiFlow 2

| | UiFlow 1 | UiFlow 2 |
|---|---|---|
| Block file | `.m5b` | `.m5b2` |
| Project file | `.m5f` | `.m5f2` |
| Container | ZIP-like | plain JSON |
| Block definition | JSON manifest + one `.py` file per block | **one Python class with YAML docstrings** |
| Block type | explicit (`"type": "value"` / `"execute"`) | **derived from the return annotation** |
| How a Periph chip's blocks get built | `python/uiflow1/generate.sh` runs the community `uiflow-custom-block-generator` (3110) | **written and exported by hand in the UiFlow 2 web IDE's Block Designer** — see [§5](#5-workflow-adding-a-chips-m5b2) |

The formats are **not** compatible and there is no official `.m5b` → `.m5b2` converter.
The existing `python/uiflow1/<category>/<chip>/*.py` block files cannot be reused as-is for
`.m5b2` — for UiFlow 2, each chip needs one annotated Python class instead.

This repo does not build a `.m5b2` generator. Unlike the 3110 tool for `.m5b`, there is no
official (or unofficial) generator for `.m5b2`, and reverse-engineering one well enough to
reproduce the format byte-for-byte (see the internals in [§2](#2-anatomy-of-a-m5b2-file))
is more risk than it's worth for a format M5Stack could change without notice. The wrapper
class is still written by hand as ordinary Python — only the `.m5b2` itself is produced by
the real Block Designer instead of a script.

---

## 2. Anatomy of a `.m5b2` file

Background for understanding what the Block Designer produces and for troubleshooting when
something looks wrong — you won't need to construct this by hand.

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
representation of it; `uiflow2` is the Blockly code generated from it. The Block Designer
derives both automatically whenever you edit the class — you never edit `data` or
`uiflow2` yourself.

**A quirk to be aware of — the header block is re-rendered.** The code shown in the Block
Designer is byte-identical to the stored `pyCode` except for one field: the `time` entry in
the module docstring carries the **current** date, while `data.header.time` keeps the
**creation** date. In the reference export, `pyCode` shows `time 2026-08-27` while
`data.header.time` still reads `2024-09-14`. This is the app's own bookkeeping — it means
the `time` field inside an exported `.m5b2`'s embedded `pyCode` will read differently every
time you re-export, even with no real change. That's expected diff noise in the committed
binary and not worth fighting.

### 2.1 `pyCode` — the source

A Python class whose docstrings contain YAML. This is the format you actually write and
paste into the Block Designer:

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
        category: Periph
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

(`category: Periph` above matches the toolbox category name `python/uiflow1/` already
groups all its blocks under — keep every chip's `.m5b2` in that same category so Periph
blocks stay together in the UiFlow 2 toolbox too.)

Rules for `label`:

- `%1` is **always** the instance field (the object instance's variable name).
- `%2`, `%3`, … are the parameters in signature order.
- The text can be localized (`en:`, plus any other language keys).

**This class is not the Periph driver.** Periph's `python/periph/chips/<category>/<chip>.py`
classes (`<Chip>Minimal` / `<Chip>Full`) have no return-type annotations — see e.g.
`python/periph/chips/power/ina219.py`, where `voltage(self):` returns a float but is not
annotated `-> float`. Since block type in `.m5b2` is derived purely from the presence and
value of a return annotation (see [§3.1](#31-the-return-annotation-decides-the-block-type)),
pasting a driver file straight into the Block Designer would misclassify every getter as a
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

## 3. Rules to know when writing the wrapper class

### 3.1 The return annotation decides the block type

| Signature | `ast_return.id` | Blockly | Python generator |
|---|---|---|---|
| `def m(self)` | `null` | `previousStatement` + `nextStatement` | `` return `${varname}.m()\n` `` |
| `def m(self) -> None` | `"None"` | `'output': null` | `return [..., ORDER_NONE]` |
| `def m(self) -> int` | `"int"` | `'output': null` | `return [..., ORDER_NONE]` |

**Merely having a return annotation makes the block a value block — even `-> None`.**
This looks like a UiFlow 2 bug, but it's the observed behavior.

Practical consequence for Periph wrapper methods: methods with no return value (`set_mode`,
`write`, `reset`) get **no** annotation. Methods that return something (`read_temperature`,
`is_data_ready`) get the real annotation (`-> float`, `-> int`, `-> bool`) even though the
underlying `Full`-class method they call is unannotated.

### 3.2 File name and class name must match exactly

The generated JS hard-codes `from <Class> import <Class>`. The wrapper file must therefore
be deployed to the device as `<ClassName>.py`.

### 3.3 A cosmetic inconsistency you'll see in the exported file

`uiflow2.block_type` lists the constructor as `custom_<lower>___init__` (three
underscores), while `jscode` registers it as `custom_<lower>_init`. This is the Block
Designer's own doing, not something to "fix" if you ever diff a committed `.m5b2` and
notice it.

---

## 4. Parameter fields

| YAML | Blockly | Toolbox shadow |
|---|---|---|
| `type: str` (no `field`) | `input_value` | `<shadow type="text">` |
| `type: int` / `float`, `field: number` | `input_value` | `<shadow type="math_number">` with `default` as `NUM` |
| `field: dropdown` + `options:` | `field_dropdown` inline | no shadow |
| — (`__init__` only) | `field_input` defaulting to `<lower>_0` | — |

`min`/`max` are carried through in `data.params`. Other field types (boolean, color, angle)
are **unverified** — if a chip needs one, build a test block in the UiFlow 2 web IDE first
and confirm it behaves as expected before relying on it.

---

## 5. Workflow: adding a chip's `.m5b2`

### Step 1 — write the wrapper class

One class per chip, class name == file name, wrapping the chip's `Full` class from
`python/periph/chips/<category>/<chip>.py` (mirroring how `python/uiflow1/<category>/<chip>/
<chip>_init.py` already wraps the `periph.connection.<transport>_auto` factory for the
`.m5b` blocks). Methods prefixed with `_` (other than `__init__`) are ignored and produce
no blocks, so internals stay hidden.

Save it at `python/uiflow2/<category>/<chip>/<Chip>.py` — mirroring `python/uiflow1/`'s
`<category>/<chip>/` layout. The body can hold real code — for a pure block definition,
`pass` is enough, but a runnable body means the same file can be dropped on a device and
imported directly.

### Step 2 — add YAML docstrings

Class docstring: `note`, `details` (with `color`, `link`, `image`, `category: Periph`),
`example`. Method docstrings: `label` and `params` as in [§2.1](#21-pycode--the-source).

Keep the color `#a6bbcf` and category `Periph` consistent with `python/uiflow1/`'s blocks
so both platforms' Periph blocks look and group the same way.

### Step 3 — build it in the UiFlow 2 Block Designer

Open the Block Designer in the UiFlow 2 web IDE, create a class-based custom block
extension, and enter the wrapper class's code in its code view — `pyCode` in
[§2.1](#21-pycode--the-source) is exactly this input format. The Designer parses the
YAML docstrings and derives the blocks automatically; no local tooling is involved.

### Step 4 — check the blocks

Drag the init block plus one block per method onto the canvas, switch to the code view, and
confirm the generated MicroPython matches what you expect (correct types, correct defaults,
correct dropdown options).

### Step 5 — export and commit

Export the `.m5b2` and save it as `python/uiflow2/<category>/<chip>/<Chip>.m5b2`, committed
alongside the wrapper class from Step 1. Then deploy the wrapper (and the `periph` package)
to a device and run it for real — the Designer's code view confirms syntax, not hardware
behavior.

There is no `--check`-style CI step for `.m5b2` the way `python/uiflow1/generate.sh --check`
verifies `.m5b`: without a generator, there's nothing local to regenerate against and diff.
Keeping the wrapper class and its exported `.m5b2` in sync is a manual discipline — re-open
the class in the Block Designer and re-export whenever the wrapper class changes.

---

## 6. Troubleshooting

The format is undocumented, so if a previously-working `.m5b2` stops behaving:

1. Build a test block in the current web IDE and export it.
2. Check `version` in the export — anything other than `alpha2` means the format changed.
3. Diff the new export against a previously committed `.m5b2` for the same chip (or against
   any known-good `.m5b2`) to see what's different.
4. Re-open the affected chip's class in the Block Designer, re-export, and commit the
   result.

**Known, cosmetic quirks in `jscode`** (harmless — don't treat these as the format having
changed):

1. UiFlow 2 leaves a commented-out `field_dropdown` line in the init block.
2. `args0` formatting varies slightly between exports (inline vs. multi-line).
3. An extra blank line sometimes appears before `return` in the generated Python.

---

## 7. Open items

Format unknowns:

- Boolean, color, and angle field types are unverified.
- Multi-language labels are supported by the format (`label.en`, other keys) but untested
  beyond `en`.
- `assignments`, `source_internal`, `source_external` are empty in every known export;
  their purpose is unknown.
- Classes with inheritance, or multiple classes per file, are untested — stick to one plain
  class per wrapper file until someone verifies otherwise.
- Whether UiFlow 2 accepts a `.m5b2` with multiple categories in one file is untested.

Resolved (2026-08-27):

- **Both `.m5b` and `.m5b2` are mandatory per chip** — UIFlow 1 and UIFlow 2 don't
  interoperate at all, so neither format can stand in for the other. `specs/_template_chip.md`
  and `specs/_template_chip_io_expander.md` now have a `### UIFlow 2` checklist section
  alongside `### UIFlow 1`.
- **Layout confirmed:** `python/uiflow2/<category>/<chip>/{<Chip>.py,<Chip>.m5b2}`, mirroring
  `python/uiflow1/`.
- **No generator will be built** — see [§1](#1-uiflow-1-vs-uiflow-2). `.m5b2` is produced by
  hand in the real UiFlow 2 Block Designer (Step 3 in [§5](#5-workflow-adding-a-chips-m5b2)),
  not by a script in this repo.

---

## 8. Sources

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
