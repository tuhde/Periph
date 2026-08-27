# UIFlow 2 native custom blocks (`.m5b2`) — reference for this repo

This document specifies the `.m5b2` format used by the current M5Stack UIFlow 2 web IDE
(uiflow2.m5stack.com) and how a Periph chip's `.m5b2` is authored for this repo. It is the
design reference for the `feature/uiflow2` effort.

> **Format status:** `.m5b2` carries the internal version tag `"alpha2"`. M5Stack has not
> published a spec for it. This document combines an initial reverse-engineering pass with
> a **verified real export** — `python/uiflow2/environmental/aht21/AHT21.m5b2` was produced
> by pasting `AHT21.py` into the actual UiFlow 2 Block Designer and exporting the result, not
> hand-guessed. Anything below marked *(verified)* comes from that export; anything marked
> *(unverified)* is still an educated guess pending a real check. Treat all of it as liable
> to break on a UiFlow 2 update — see [Troubleshooting](#6-troubleshooting).

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

A UiFlow 2 **project** (`.m5f2`) is also plain JSON at the top level, but its `blockly` key
holds Blockly's standard workspace XML *as a string* — block instances placed on the
canvas (`<block>`/`<value>`/`<shadow>`/`<field>`/`<mutation>`/`<next>`), not the `.m5b2`
block-*definition* format from §2. A project referencing a `Custom` class-based block does
not appear to embed that block's `jscode`/`pyCode`/definition — see
[§4.1](#41-leads-from-a-built-in-device-block-not-confirmed-for-custom_-class-based-blocks).

The formats are **not** compatible and there is no official `.m5b` → `.m5b2` converter.
The existing `python/uiflow1/<category>/<chip>/*.py` block files cannot be reused as-is for
`.m5b2` — for UiFlow 2, each chip needs one annotated Python class instead.

This repo does not build a `.m5b2` generator. Unlike the 3110 tool for `.m5b`, there is no
official (or unofficial) generator for `.m5b2`. The wrapper class is written by hand as
ordinary Python; the `.m5b2` itself is produced by the real Block Designer instead of a
script — see [§5](#5-workflow-adding-a-chips-m5b2). `python/uiflow2/environmental/aht21/`
is a verified worked example: read its `AHT21.py` and `AHT21.m5b2` side by side alongside
this document when building the next chip.

---

## 2. Anatomy of a `.m5b2` file

Five top-level keys plus `version` *(verified)*:

```
{
  "category": "<ClassName>",
  "color":    "#C084FC",
  "uiflow2":  { jscode, toolbox, toolboxitemid, block_type },
  "data":     { name, note, details, header, assignments, example,
                source_internal, source_external, members, python_file_name },
  "pyCode":   "<complete Python source as a string>",
  "version":  "alpha2"
}
```

**`block_type` is a plain list of block-type strings** *(verified)*, one per method in
signature order (`__init__` first) — not an object keyed by method name as an earlier draft
of this document guessed:

```json
["custom_aht21___init__", "custom_aht21_read_temperature", "custom_aht21_read_humidity"]
```

(`#C084FC` above is Periph's existing block color, matching the palette already used for
`python/uiflow1/` and the Node-RED nodes — reuse it for `.m5b2` blocks too, per
`python/uiflow1/README.md`.)

**Key fact: `pyCode` is the single source of truth.** `data` is the parsed AST
representation of it; `uiflow2` is the Blockly code generated from it. The Block Designer
derives both automatically whenever you edit the class — you never edit `data` or
`uiflow2` yourself.

**The Block Designer reformats `pyCode` on import/export** *(verified)* — don't expect the
committed `.m5b2`'s embedded `pyCode` to be byte-identical to the `.py` file you pasted in:

- The `time` entry in the module docstring is rewritten to the **current** date on every
  export, while `data.header.time` keeps the original **creation** date. That means the
  `time` field inside `pyCode` will read differently on every re-export even with no real
  change — expected diff noise, not worth fighting.
- Empty header fields (`author`, `email` when left blank) come back with trailing spaces
  for column alignment (e.g. `"author   \n"`) — don't try to match this by hand in the `.py`
  source; it's the Designer's own formatting.
- Blank-line spacing around the class docstring and before the first method is normalized
  and doesn't necessarily match what you typed.
- **`details.category` is always rewritten to `Custom`**, regardless of what you write in
  the docstring *(verified — see [§2.1](#21-pycode--the-source))*.

### 2.1 `pyCode` — the source

A Python class whose docstrings contain YAML. This is the format you actually write and
paste into the Block Designer — `python/uiflow2/environmental/aht21/AHT21.py` is a real,
verified example; here's the general shape:

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
        color: '#C084FC'
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

**`category: Custom` is not optional decoration — write it, but don't expect anything else
to work.** The AHT21 wrapper originally used `category: Periph` (to match the toolbox
category `python/uiflow1/` groups its blocks under), but the real Block Designer silently
rewrote it back to `Custom` on export, in both `pyCode` and `data.details.category`. There
is currently no known way to give a chip's `.m5b2` its own toolbox category the way
`python/uiflow1/`'s manifests do — every Periph chip's UIFlow 2 blocks land under the
generic `Custom` category. Whether some other, undiscovered mechanism controls this is an
open item — see [§7](#7-open-items).

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
  "name": "read_temperature",
  "note": {},
  "label": { "en": "%1 read temperature (°C)" },
  "params": [],
  "return": "",
  "source": "        return self._driver.read_temperature()",
  "ast_return": { "code": "self._driver.read_temperature()", "id": "float" },
  "doc_return": null
}
```

(Real member from `AHT21.m5b2`'s `data.members`, *verified*.)

`source` holds the method body indented 8 spaces, with the docstring stripped out.
`ast_return.code` holds the source text of the returned expression when the method body
ends in `return <expr>` — **null only when there's no return statement to capture** (e.g.
`__init__`, or a body of just `pass`). An earlier draft of this document assumed `code` was
always `null`, based on the abstract example in [§2.1](#21-pycode--the-source) whose
`method2` body was `pass` — that was a body with nothing to return, not a general rule.

### 2.3 `uiflow2.jscode` — generated Blockly code

Verified, from `AHT21.m5b2` — adapt this template directly for the next chip rather than
guessing at the shape:

```javascript
const CUSTOM_AHT21_LANGUAGES = {
  "CUSTOM_AHT21_INIT": {
    "en": "%1 init I2C bus %2 address %3"
  },
  "CUSTOM_AHT21_READ_TEMPERATURE": {
    "en": "%1 read temperature (°C)"
  },
  "CUSTOM_AHT21_READ_HUMIDITY": {
    "en": "%1 read humidity (%)"
  }
};

const initType = 'custom_aht21_init';
Blockly.BlockRegExpList['custom_aht21'] = {
  regexp: new RegExp(/^custom_aht21_/),
  code: "from AHT21 import AHT21",
  initBlockType: initType,
  categoryId: 'custom_aht21',
}
Blockly.utils.registerLanguages(CUSTOM_AHT21_LANGUAGES)

Blockly.Msg.CUSTOM_AHT21_HUE = '#C084FC'
Blockly.Msg.CUSTOM_AHT21 = 'AHT21'

Blockly.utils.getcustom_aht21Options = function() {
  let options = [];
  let list = Blockly.utils.getCustomNameList(initType);
  for (let i = 0; i < list.length; i++) {
    let value = list[i];
    options.push([String(value), String(value)]);
  }
  if (options.length === 0) return [
    ['aht21_0', 'aht21_0']
  ];
  return options;
}

Blockly.Blocks["custom_aht21_init"] = {
  init: function() {
    this.jsonInit(this._init());
  },
  _init: function() {
    return {
      'message0': Blockly.Msg.CUSTOM_AHT21_INIT,
      'args0': [
        // { 'type': 'field_dropdown', 'name': 'NAME', 'options': Blockly.utils.getcustom_aht21Options },
        {
          'type': 'field_input',
          'name': 'NAME',
          'text': 'aht21_0'
        },
        {
          'type': 'input_value',
          'name': 'bus'
        }, {
          'type': 'input_value',
          'name': 'address'
        },
      ],
      'previousStatement': null,
      'nextStatement': null,
      'inputsInline': true,
      'colour': "#C084FC",
      "tool": []
    };
  }
}

Blockly.Python["custom_aht21_init"] = function(block) {
  var varname = block.getFieldValue('NAME') || '_';
  var bus = Blockly.Python.valueToCode(block, 'bus', Blockly.Python.ORDER_FUNCTION_CALL);
  var address = Blockly.Python.valueToCode(block, 'address', Blockly.Python.ORDER_FUNCTION_CALL);
  return `${varname} = AHT21(${bus}, ${address})\n`
}

Blockly.Blocks["custom_aht21_read_temperature"] = {
  init: function() {
    this.jsonInit(this._init());
  },
  _init: function() {
    return {
      'message0': Blockly.Msg.CUSTOM_AHT21_READ_TEMPERATURE,
      'args0': [{
          'type': 'field_dropdown',
          'name': 'NAME',
          'options': Blockly.utils.getcustom_aht21Options
        },

      ],
      'output': null,
      'inputsInline': true,
      'colour': "#C084FC",
    };
  }
}

Blockly.Python["custom_aht21_read_temperature"] = function(block) {
  var varname = block.getFieldValue('NAME') || '_';

  return [`${varname}.read_temperature()`, Blockly.Python.ORDER_NONE]
}

// custom_aht21_read_humidity follows the exact same pattern as
// custom_aht21_read_temperature above.
```

Notes on what this actually means, per bullet:

1. **`CUSTOM_<UPPER>_LANGUAGES`** — keyed `CUSTOM_<CLASS>_<METHOD>` (uppercase), each value
   an object of `{lang: text}` (only `en` seen so far), fed through
   `Blockly.utils.registerLanguages(...)` — a real UiFlow 2 API, not something this repo
   implements.
2. **`Blockly.BlockRegExpList['custom_<lower>']`** carries four fields: `regexp` (matches
   every block type belonging to this chip), `code` (the import line UiFlow 2 writes into
   generated MicroPython — this is how the import gets injected; block Python generators
   don't emit it themselves), `initBlockType`, and `categoryId`.
3. **`Blockly.Msg.CUSTOM_<UPPER>_HUE`** and **`Blockly.Msg.CUSTOM_<UPPER>`** are set as
   plain assignments (color and display name), separately from the LANGUAGES table.
4. **`Blockly.utils.getcustom_<lower>Options`** wraps a real API,
   `Blockly.utils.getCustomNameList(initBlockType)`, which returns the names of existing
   instances of the init block on the workspace; falls back to `['<lower>_0', '<lower>_0']`
   when there are none yet.
5. Each block is defined via `Blockly.Blocks[type] = { init, _init }`, where `_init()`
   returns the Blockly JSON block definition — **this return value is a real JS object
   literal, not JSON**: it can (and does) hold a live function reference
   (`'options': Blockly.utils.getcustom_aht21Options`, unquoted) and a comment. The
   commented-out `field_dropdown` line inside the init block's `args0` is not an artifact of
   a particular chip — it appeared even though `AHT21.py`'s `__init__` has no dropdown
   parameter, so expect it in every chip's init block. `'inputsInline': true` is present on
   every block. `colour` is the literal hex string, not a `Blockly.Msg` reference, even
   though the Msg entry from bullet 3 also holds it.
6. The instance-name field is always named **`'NAME'`**, not `'VAR'`.
7. `custom_<lower>_init`'s Python generator reads `bus`/`address` via
   `Blockly.Python.valueToCode(block, '<param>', Blockly.Python.ORDER_FUNCTION_CALL)` (no
   fallback default beyond what the toolbox shadow block already provides) and returns
   `` `${varname} = <ClassName>(${param1}, ${param2}, ...)\n` `` — positional arguments, in
   signature order, not keyword arguments.
8. Every other (value) block's Python generator matches rule 3.1's table exactly: `return
   [`${varname}.<method>()`, Blockly.Python.ORDER_NONE]`.

### 2.4 `uiflow2.toolbox` — XML fragment

Verified, from `AHT21.m5b2`:

```xml
<category name="AHT21" colour="#C084FC" hidden="true" toolboxitemid="custom_aht21">
<title text="AHT21" docsLink="https://github.com/tuhde/Periph"></title>
<block type="custom_aht21_init">
  <value name="bus">
    <shadow type="math_number">
      <field name="NUM">0</field>
    </shadow>
  </value>
  <value name="address">
    <shadow type="math_number">
      <field name="NUM">56</field>
    </shadow>
  </value>
</block><block type="custom_aht21_read_temperature"/><block type="custom_aht21_read_humidity"/>
</category>
```

`name` and `colour` are literal strings (not `Blockly.Msg` placeholders) in the exported
XML. The init block's toolbox entry only overrides its `input_value` params with
`<shadow type="math_number">` blocks (`NUM` = the field's `default`) — it does **not**
repeat a `<field name="NAME">` override, since the block's own JSON definition
(`'text': 'aht21_0'` in [§2.3](#23-uiflow2jscode--generated-blockly-code)) already supplies
that default. Value-type blocks (`read_temperature`, `read_humidity`) get bare self-closing
`<block .../>` tags with no children.

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
underlying `Full`-class method they call is unannotated. Confirmed against `AHT21.m5b2`:
`read_temperature() -> float` and `read_humidity() -> float` both produced `'output': null`
value blocks returning `[..., Blockly.Python.ORDER_NONE]` exactly as this table predicts.

### 3.2 File name and class name must match exactly

The generated JS hard-codes `from <Class> import <Class>` (in `BlockRegExpList`'s `code`
field, *verified*). The wrapper file must therefore be deployed to the device as
`<ClassName>.py`.

### 3.3 A cosmetic inconsistency in the exported file

`uiflow2.block_type` lists the constructor as `custom_<lower>___init__` (three
underscores), while `jscode` registers the actual Blockly block type as `custom_<lower>_init`
(one underscore) — *verified*, `AHT21.m5b2`'s `block_type` list contains
`"custom_aht21___init__"` while every `Blockly.Blocks[...]`/`Blockly.Python[...]` reference
in its `jscode` uses `"custom_aht21_init"`. `custom_aht21___init__` does not appear to be
registered as an actual Blockly block anywhere — this is the Block Designer's own doing, not
something to "fix" if you see it in a diff.

---

## 4. Parameter fields

| YAML | Blockly | Toolbox shadow |
|---|---|---|
| `type: str` (no `field`) | `input_value` | `<shadow type="text">` |
| `type: int` / `float`, `field: number` | `input_value` | `<shadow type="math_number">` with `default` as `NUM` |
| `field: dropdown` + `options:` | `field_dropdown` inline | no shadow |
| — (`__init__` only) | `field_input` named `'NAME'`, defaulting to `<lower>_0` | — |

The `int`/`float` row is verified against `AHT21.m5b2` (`bus`/`address` both produced
`input_value` args with `math_number` toolbox shadows carrying the param's `default` as
`NUM`). `min`/`max` are carried through in `data.params` but don't appear to constrain the
generated block itself (no `min`/`max` attribute was found anywhere in `jscode` or
`toolbox` for `AHT21`'s bus/address params — they may only be used for the Designer's own
UI-side input validation, not enforced in the exported block). Other field types (boolean,
color, angle) remain **unverified** — if a chip needs one, build a test block in the UiFlow
2 web IDE first and confirm it behaves as expected before relying on it.

### 4.1 Leads from a built-in device block (not confirmed for `custom_` class-based blocks)

`python/uiflow2/cores3_switchc6_example.m5f2` is a **project** file (`.m5f2`), not a block
definition — its `blockly` field is Blockly's standard workspace XML (block instances
placed on a canvas: `<block>`, `<value>`, `<shadow>`, `<field>`, `<mutation>`, `<next>`,
top-level `<variables>`), a different container from `.m5b2`'s JSON entirely. It uses
M5Stack's official **`iot_switchc6_*`** blocks — a pre-built "IoT" device family shipped
with the app, not a user-authored `Custom`-category class-based extension. The project
carries no `jscode`/`pyCode`/`BlockRegExpList` for it (`customList` is empty), so nothing
here is a verified extension of the `custom_`-block rules in §2–§3. Still, two things it
shows are worth knowing as leads for when a chip eventually needs them:

- **A `math_slider` shadow type exists**, distinct from `math_number`:
  `<shadow type="math_slider"><mutation max="14" min="0" step="1" precision="1"/><field
  name="NUM">0</field></shadow>`. Whether a `.m5b2` wrapper class can request this via some
  YAML `field:` value (`field: slider`?) is unconfirmed — AHT21's own `math_number` shadows
  carried no `mutation` at all despite having `min`/`max` in the YAML, so it's not even
  confirmed that per-param `min`/`max` reliably produces a constrained shadow via the
  class-based path (see §7).
  - Also notable: this `math_number`/`math_slider` distinction is the first *direct*
    evidence that a `mutation max="…" min="…" precision="…"` on a numeric shadow is a real,
    supported Blockly feature in this app — AHT21's lack of one may be a Designer quirk
    for the class-based path specifically, not a general format limitation.
- **A likely pattern for boolean-ish parameters:** `iot_switchc6_set_switch_option` is a
  whole dedicated shadow *block type* (not a native checkbox field) with a single
  `<field name="VALUE">False</field>`. If the same convention applies to `custom_` blocks, a
  `type: bool` YAML param might generate its own `custom_<lower>_set_<param>_option`-style
  shadow block rather than mapping onto a plain Blockly field — but this is a hypothesis
  from a different block family, not something to build against without checking it in the
  Designer first.
- Unrelated to parameter types, but worth knowing if a chip's blocks ever need it: built-in
  device blocks address a specific unit by a literal `MAC`/id **field** embedded directly in
  each block (not the instance-`NAME`-dropdown pattern `custom_` blocks use), and there's a
  distinct `iot_..._event_callback` block type for async event handlers with multiple bound
  output variables. Neither pattern has an equivalent in the `custom_` class-based blocks
  documented here.

---

## 5. Workflow: adding a chip's `.m5b2`

### Step 1 — write the wrapper class

One class per chip, class name == file name, wrapping the chip's `Full` class from
`python/periph/chips/<category>/<chip>.py` (mirroring how `python/uiflow1/<category>/<chip>/
<chip>_init.py` already wraps the `periph.connection.<transport>_auto` factory for the
`.m5b` blocks). Methods prefixed with `_` (other than `__init__`) are ignored and produce
no blocks, so internals stay hidden.

Save it at `python/uiflow2/<category>/<chip>/<Chip>.py` — mirroring `python/uiflow1/`'s
`<category>/<chip>/` layout. `python/uiflow2/environmental/aht21/AHT21.py` is a working
example: `__init__(self, bus: int = 0, address: int = 56)` constructs the `Full` class over
`periph.connection.i2c_auto.I2CConnection`, and each other method delegates straight to the
matching `Full`-class call.

### Step 2 — add YAML docstrings

Class docstring: `note`, `details` (with `color: '#C084FC'`, `link`, `image: ''`, and
`category: Custom` — see [§2.1](#21-pycode--the-source), it can't currently be anything
else), `example`. Method docstrings: `label` and `params` as in
[§2.1](#21-pycode--the-source).

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

**On confidence without a generator:** §2.3/§2.4 above are no longer guesses — they're a
verified template pulled from a real export. For a chip whose methods look like AHT21's
(no-arg getters returning a single value, an init with only `int`/`float` params), copying
that template and substituting names is very likely to match what the Designer itself would
produce. For anything structurally different — a `field: dropdown` param, a chip needing a
field type from [§4](#4-parameter-fields)'s unverified row — still build and check it in the
real Block Designer rather than extrapolating.

**Every other chip under `python/uiflow2/` besides AHT21 was built exactly this way** — by
mechanically extending the verified template to methods *with* parameters and execute-type
blocks *with* parameters, neither of which AHT21 itself exercised (see
`python/uiflow2/README.md`'s verification-status note for the full reasoning and the specific
design choices made, e.g. representing boolean-ish parameters as `type: int` rather than an
unverified field type). Treat all of them as a checked-in first draft, not a finished,
Designer-confirmed deliverable, until each has actually been imported and verified.

---

## 6. Troubleshooting

The format is undocumented, so if a previously-working `.m5b2` stops behaving:

1. Build a test block in the current web IDE and export it.
2. Check `version` in the export — anything other than `alpha2` means the format changed.
3. Diff the new export against a previously committed `.m5b2` for the same chip (or against
   `python/uiflow2/environmental/aht21/AHT21.m5b2` as a known-good baseline) to see what's
   different.
4. Re-open the affected chip's class in the Block Designer, re-export, and commit the
   result.

**Known, cosmetic quirks** (harmless — don't treat these as the format having changed, or
as bugs to fix):

1. The commented-out `field_dropdown` line in every init block's `args0` (§2.3 bullet 5).
2. `pyCode`'s `time` field, blank-line spacing, and trailing whitespace on empty header
   fields churn on every export (§2, "The Block Designer reformats `pyCode`...").
3. `details.category` always comes back `Custom` no matter what you write (§2.1).

---

## 7. Open items

Format unknowns:

- **Whether a chip's UIFlow 2 blocks can be grouped under anything other than the generic
  `Custom` toolbox category.** `category: Custom` is the only value seen so far (both in the
  original reverse-engineered `ExampleClass.m5b2` and in the verified `AHT21.m5b2`) — see
  [§2.1](#21-pycode--the-source). If every Periph chip's blocks land under `Custom`
  side-by-side with everyone else's custom blocks, that's a real UX gap worth raising with
  M5Stack or digging into further, not just a cosmetic detail.
- Whether `min`/`max` on a numeric param does anything beyond being carried in
  `data.params` — no evidence of it constraining the generated block or its toolbox shadow
  (§4). A different, built-in M5Stack block (not class-based — see §4.1) does show numeric
  shadows carrying a real `mutation max/min/precision`, and a distinct `math_slider` shadow
  type with `step` too, so the underlying capability exists in the app; whether the `.m5b2`
  class-based generator path can produce either is still unconfirmed.
- Boolean, color, and angle field types are unverified for `custom_` class-based blocks. One
  lead (§4.1, from a built-in block, not confirmed to apply here): boolean-ish parameters
  might generate their own dedicated shadow block type rather than a plain checkbox field.
- Multi-language labels are supported by the format (`label.en`, other keys) but untested
  beyond `en`.
- `data.assignments` is an empty list and `data.source_internal` is an empty string in every
  known export; their purpose is still unconfirmed. `data.source_external`, however, is
  **now confirmed** (§2.2): it holds the module-level source outside the class — for AHT21,
  the two `from ... import ...` lines above the class definition. `source_internal` is
  presumably the analogous slot for code inside the class but outside any method (e.g. class
  attributes); untested since AHT21's wrapper has none.
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
  hand in the real UiFlow 2 Block Designer (Step 3 in [§5](#5-workflow-adding-a-chips-m5b2)).
- **First hand-built `.m5b2` (AHT21) was wrong** — the first pass at this document (before
  any real export existed) guessed `block_type` was an object rather than a list, guessed
  `category: Periph` would be honored, guessed `ast_return.code` was always `null`, and
  guessed at `jscode`/`toolbox` internals that turned out meaningfully different from the
  real thing (see the *(verified)* markers throughout this document for the corrections).
  Lesson: don't hand-author a `.m5b2` you can't check against the real Block Designer.

---

## 8. Sources

- Reverse engineering of a UiFlow 2 export (`ExampleClass.m5b2`, `version: alpha2`)
- `python/uiflow2/environmental/aht21/AHT21.m5b2` — a real export from the UiFlow 2 Block
  Designer (2026-08-27), used to verify and correct this document
- `python/uiflow2/cores3_switchc6_example.m5f2` — a real UiFlow 2 project (2026-08-27) using
  M5Stack's built-in `iot_switchc6_*` device blocks; source for the `.m5f2` project-file
  observations in this section and the leads in [§4.1](#41-leads-from-a-built-in-device-block-not-confirmed-for-custom_-class-based-blocks)
- Comparison of the stored `pyCode` against the code the Block Designer displays in its UI:
  byte-identical except for the `time` field (see [§2.1](#21-pycode--the-source)). This
  confirms the YAML-in-docstring convention is exactly the Designer's own input format, not
  merely a serialization of it.
- M5Stack community, thread 6837 "Any examples of tutorial on .m5b2 new custom block
  system?" — an M5Stack staff member confirms return values are declared via type
  annotations (`def func(self) -> int`).
- M5Stack community, thread 6524 — confirms there is no `.m5b` → `.m5b2` converter.
- For UiFlow 1, for comparison: https://github.com/3110/uiflow-custom-block-generator
