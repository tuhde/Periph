# Spec: Sigrok Decoder Annotations Rework

**Scope:** cross-cutting rework of every existing sigrok protocol decoder (`sigrok/<chip>/pd.py`) and the conformance-checker layer that reads their annotation text. No datasheet — this spec is produced and consumed the same way `specs/testing_framework.md` is, since it is not tied to one chip, category, or transport.

**Tracking issue:** #111 ("rework sigrok decoder annotations": use variable-length annotations, use consistent categories over all chips, include useful annotations)

## Overview

38 sigrok decoders exist today (`sigrok/*/pd.py`, one per chip plus a few shared transport-level decoders: `dhtxx`, `sipo`, `neopixel`). They grew one PR at a time, each free to invent its own annotation-row names and its own `put()` string-list shape. The result is inconsistent in exactly the three ways issue #111 names, and — because `conformance/_sigrok_conformance.py` parses these decoders' own annotation *text* to implement HIL-conformance timing checks (see `specs/testing_framework.md`, "Conformance Implementation") — the inconsistency isn't just cosmetic: some of today's shortcuts are load-bearing for the test framework and must be handled carefully during the retrofit, not just reformatted.

This spec defines the target convention and enumerates every decoder (and the conformance checkers keyed to it) that needs updating to reach it.

## Investigation Findings

### 1. Annotation-row category names are inconsistent

The same kind of content is named differently across decoders (row id / row title):

| Concept | Row names actually used today |
|---|---|
| Per-transaction register/protocol content | `data`/`Data` (most), `regs`/`Registers` (`ens160` only) |
| Conformance/HIL timing-marker start/end pairs | `timing`/`Timing` (majority: `apds9960`, `bme280`, `bme680`, `bmp180`, `bmp280`, `ina226`, `ina3221`, `lps33hw`, `mcp23017`, `mcp4725`, `mcp4728`, `mfrc522`, `pcf8574`, `pcf8575`), `conformance`/`Conformance` (`24aa02uid`, `adxl345`, `as5600`, `ina219`, `mpu6050`, `rda5807m`), `power`/`Power` (`hx711`, `hx710a` — mixes a status event and a timing span under a name that matches neither convention), four separate bespoke rows `poweron`/`conversion`/`als_integration`/`proximity_integration` (`apds9930`, one row per check instead of one shared row) |
| Malformed-transaction notices | `warnings`/`Warnings` (all decoders that have one) — **missing entirely** on `sipo` |

Issue #111's "use consistent categories over all chips" is this table collapsing to one row name per concept.

### 2. "Variable length" is inconsistently applied, and one common pattern actively defeats it

AGENTS.md's existing rule ("provide at least two strings per `put()` call: a long form and a short form") is followed for ordinary data annotations, but two systematic gaps exist:

- **Warning annotations almost always supply only one string.** `self.put(ss, es, self.out_ann, [ANN_WARNING, [msg]])` appears verbatim (single-element list) in nearly every decoder. At a narrow zoom sigrok has nothing shorter to fall back to.
- **Timing-marker (start/end) annotations typically supply two *identical* strings** — e.g. `ina219`'s `['wake_write', 'wake_write']`, `mcp23017`'s `['register_read_start']` (single-element), `bme280`'s `['measurement_trigger_start (ctrl_meas mode=Forced)', 'MEAS_START']` (this one is fine). The duplicated-string pattern isn't an oversight: it's a workaround so that whichever string a consumer reads, the substring a conformance checker matches on is guaranteed present. But it means these annotations are not actually variable-length — they don't shrink at all.

### 3. sigrok-cli's non-interactive decode always emits the *first* (longest) annotation string

This is the reason the duplicated-string workaround above exists, and it constrains how the fix must be shaped. `conformance/_sigrok_conformance.py:decode_annotations()` runs `sigrok-cli -P <bus>,<chip>` with `--protocol-decoder-samplenum` and regex-parses one line per `put()` call — there is no `-A <decoder>=<class>` width negotiation in this path, so sigrok-cli prints annotation-text-list index **0** unconditionally. Every existing conformance checker's predicate (`conformance/*/​*_conformance.py`) was written against that string. **Any retrofit that turns tier‑0 into a longer, more descriptive string must keep the checker's matched substring present verbatim in tier 0** — moving it to a later, shorter tier does not help a headless conformance run, only PulseView's on-screen truncation.

### 4. Two conformance checkers use exact string equality, not substring containment

`conformance/imu/mpu6050_conformance.py` and `conformance/comms/rda5807m_conformance.py` use `lambda t: t == 'reset_recovery_start'` (and similar) rather than `lambda t: 'reset_recovery_start' in t`, because today's decoders happen to `put()` that exact bare token as tier 0 with nothing else. The moment `mpu6050`/`rda5807m`'s timing-marker tier 0 gains any surrounding descriptive text (required by this rework), these two checkers break. **This is the single easiest thing to miss during the retrofit** — every other checker already uses substring containment and is unaffected by tier-0 wording changes, these two are not.

### 5. Several checkers don't use a dedicated timing annotation at all — they key off ordinary Data-row text

`ens160` (`'Validity: Warm-up'` / `'Validity: OK'` / `'DATA_AQI:'`), `bmp581` (`'soft reset'` / `'nvm_rdy'`), `aht21` (`'Trigger Measurement'` / `'IDLE'`), `hx711`/`hx710a` (`'Power-down'`, matched against the annotation's own span rather than a start/end pair — see `find_delta_samples()`'s `is_end=None` case), and `sk6812rgbw`/`ws2812b` (`'Reset'`, likewise the underlying `neopixel` decoder's own reset-span annotation). `ens160`'s checker docstring is explicit about why: *"unlike a brand-new chip, ENS160's decoder predates this convention and its existing text already carries everything these two checks need without risking behavior change."* This rework removes that excuse for the ones being touched anyway — see **Retrofit Scope** below — but the underlying pattern (a real, useful status value doubling as an implicit timing marker) is worth keeping, just promoted into a first-class row instead of staying buried in a generic register-read string. That promotion is exactly what the new `status` row (below) is for.

### 6. `OUTPUT_PYTHON` (machine-readable stacking output) exists on only 2 of 38 decoders

Only `hx711` and `neopixel` register `srd.OUTPUT_PYTHON`. Every other decoder's decoded values are only reachable by scraping annotation text.

## Canonical Annotation Row Taxonomy

Every decoder's `annotation_rows` is organized into four **tiers**, always in this order (a tier is skipped if the decoder has nothing for it):

| Tier | Row id | Row title | Contents | Mandatory? |
|---|---|---|---|---|
| 1 | `data` (or named sub-rows, see below) | `Data` | Per-transaction protocol content: register read/write with decoded fields, or the natural protocol unit for non-register decoders (byte/frame/pixel/sentence). | Yes |
| 2 | `status` | `Status` | Discrete state/flag/validity content that is *not* a measured value — chip mode, ready/busy, ACK-like validity, error/overflow flags — split out from tier-1 text so it can be scanned as its own timeline. New tier; add only where the criterion below is met. | No |
| 3 | `timing` | `Timing` | Every conformance/HIL timing-marker annotation: start/end pairs, repeating single-marker checks (e.g. `conversion_ready`), and self-contained-span checks (e.g. HX711's power-down pulse). Replaces `conformance`, `power`, `poweron`, `conversion`, `als_integration`, `proximity_integration`, and any other bespoke name used for this purpose today. | Yes, for every decoder backing at least one conformance check |
| 4 | `warnings` | `Warnings` | Malformed transactions / protocol violations. | Yes, on every decoder (add to `sipo`, which currently has none) |

**Tier-1 sub-rows are allowed and already correct where the protocol has natural substructure** — e.g. `dhtxx`'s `frames`/`bits`/`bytes`, `sipo`'s `bytes`/`latches`/`clears`/`disableds`, `neopixel`'s `bits`/`bytes`/`resets`, `hx711`/`hx710a`'s `ready`/`bits`/`conversions`, `neo6`'s `sentences`/`fields`. Keep these; do not force-merge them into one literal row named `data`. The rule this taxonomy enforces is tier *order* and tier-3/4 *naming*, not a single row per decoder.

**Exception — core protocol framing that happens to also be a timing check's target stays in its natural tier-1 row.** `neopixel`'s `resets` row and `hx711`/`hx710a`'s `ready` row are structural parts of every capture (not diagnostic-only overlays added for conformance), so they stay where they are even though a conformance checker also reads them. HX711/HX710A's `wakeup` annotation (a derived duration measurement, `show_wakeup` option) and `powerdown` annotation *do* move to `timing` — they are diagnostic overlays, not required framing.

**`status` addition criterion:** add a `status` row only when a chip has a register/bitfield whose sole job is reporting state (not a measured value) **and** that state is already read as a side effect of decoding another register (no extra bus traffic needed) — e.g. INA219/INA226/INA3221's CNVR/OVF bits (currently folded into the Bus Voltage read's text), BME280/BME680/BMP280/BMP384/BMP581's `status`/`STATUS` register (measuring/im_update/nvm_rdy), MCP23017/PCF8574/PCF8575's interrupt-flag registers, ENS160's `DEVICE_STATUS` validity field, AHT21's busy/calibrated flags. This list is illustrative, not exhaustive — apply the criterion per chip during retrofit rather than treating it as the complete set.

## Variable-Length Annotation Convention

Replaces AGENTS.md's "at least two strings" floor with a per-content-kind minimum:

- **Data/status annotations:** at least 3 tiers — long (full decode with computed values/units), medium (name + key value, today's typical "short form"), short (2–8 character abbreviation for narrow zoom). Most decoders already have tiers 1–2; add tier 3.
- **Warning annotations:** at least 2 tiers — full message, then a short tag/code (e.g. `'Unexpected read length 3 for Bus Voltage'` / `'LEN?'`). Today's single-string warnings need a second tier added.
- **Timing-marker annotations:** a fixed 3-tier shape, *not* the same shape as data/status, because tier 0 is what headless conformance decoding reads (see Finding 3):
  1. **Tier 0 (long, checker-visible):** `'<check_name>: <human description>'` — must contain the literal `<check_name>` token verbatim (the same string used as the key in `specs/<category>/<chip>_timing.conf` and in the checker's `CHECKS` table).
  2. **Tier 1 (medium):** `'<check_name>'` alone — replaces today's duplicated-tier-0 pattern; still a valid substring match for any checker, and is what today's decoders already put at tier 0, so it's a safe fallback shape.
  3. **Tier 2 (short, PulseView-only):** a compact glyph/code that need not contain `<check_name>` (e.g. `'→PWR'`), since sigrok-cli headless decoding never reaches this tier.

  Example (`ina219`'s `wake_write` marker): `['wake_recovery: Configuration write with active MODE', 'wake_write', '→WAKE']`.

### Required companion fix: exact-match checkers

Update `conformance/imu/mpu6050_conformance.py` and `conformance/comms/rda5807m_conformance.py` to use substring containment (`'reset_recovery_start' in t`, `'gyro_startup_start' in t`, `'ready_settle_start' in t`, etc.) instead of `==`, matching every other checker. Without this, retrofitting `mpu6050`/`rda5807m`'s decoders to the tier-0 shape above breaks their conformance checks immediately.

### Required companion review: Data-text-piggyback checkers

For each decoder in Finding 5 (`ens160`, `bmp581`, `aht21`, `hx711`, `hx710a`, `sk6812rgbw`, `ws2812b`), retrofitting tier-0 text for the underlying Data/status annotation must either (a) keep the exact matched substring intact in the new tier-0 text, or (b) promote the value into the new `status` or `timing` row and re-point the checker at that row's tier-0 text instead. Either is acceptable; **the checker's predicate and the decoder's new tier-0 text must be updated in the same commit** and sanity-checked by running the checker's own predicate against a sample decoded line (or replaying `sigrok/tests/<chip>/*.sr` where one exists) before merging that chip.

## New / Useful Annotations

- **`status` row** (see taxonomy above) — the concrete, reviewable answer to "include useful annotations": promotes state that's currently prose-buried inside a Data annotation (or, worse, invisible except to a conformance checker's regex) into its own scannable timeline.
- **`OUTPUT_PYTHON` on every decoder.** Register `srd.OUTPUT_PYTHON` (not just `hx711`/`neopixel`) and emit `(kind, value)` tuples mirroring each `data`/`status` annotation — e.g. `('REG_READ', (reg, raw_value))`. Purely additive (no existing consumer is affected); gives external scripts and future stacked decoders a machine-readable feed instead of requiring annotation-text scraping. This directly enables, but does not itself implement, a future all-Python conformance decode path that wouldn't need `_ANNOTATION_RE` text-scraping at all — out of scope here, just noted as the payoff.
- **Considered and deliberately deferred:** a generic transaction-duration (STOP−START span) annotation on every decoder. Rejected — the underlying `i2c`/`spi` sigrok decoders already show this implicitly via block width in PulseView, and a duplicate annotation not tied to a spec'd timing constraint would just be clutter. Recorded here so it isn't silently re-proposed.

## Retrofit Scope

This is a full retrofit of existing decoders, not a going-forward-only convention for new chips — matching the precedent set by the C++-platform rollouts (land the convention once, backfill every existing chip in the same branch effort). All 38 directories under `sigrok/` are in scope.

### Per-decoder inventory

| Decoder | Current `annotation_rows` | Target | Notes |
|---|---|---|---|
| `24aa02uid` | data, warnings, conformance | data, warnings, timing | rename only |
| `adxl345` | data, warnings, conformance | data, warnings, timing | rename only |
| `aht21` | data, warnings | data, warnings, timing | promote poweron/trigger status into timing (see Finding 5); checker keys off `'Trigger Measurement'`/`'IDLE'` today |
| `apds9930` | data, warnings, poweron, conversion, als_integration, proximity_integration | data, warnings, timing | merge four bespoke rows into one `timing` row (4 annotation classes) |
| `apds9960` | data, warnings, timing | unchanged | already correct |
| `as5600` | data, warnings, conformance | data, warnings, timing | rename only |
| `bme280` | data, warnings, timing | unchanged | already correct |
| `bme680` | data, warnings, timing | unchanged | already correct |
| `bmp180` | data, warnings, timing | unchanged | already correct |
| `bmp280` | data, warnings, timing | unchanged | already correct |
| `bmp384` | data, warnings | data, warnings | no conformance checker yet; add `status` row only if a status/ready field exists in the spec |
| `bmp581` | data, warnings | data, warnings, timing | promote `soft reset`/`nvm_rdy` text into a timing row; checker uses substring match already so this is a straightforward promotion |
| `dhtxx` | frames, bits, bytes, warnings | unchanged (tier-1 sub-rows) | `dht11` checker keys off frame text (`t.startswith('Start:')`) — verify after any Data-tier wording change |
| `ens160` | regs, warnings | data, status, warnings, timing | rename `regs`→`data`; split `DEVICE_STATUS` validity into `status`; promote warmup/measurement-cycle markers into `timing`; re-point checker per "Required companion review" above |
| `hx710a` | ready, bits, conversions, power, warnings | ready, bits, conversions, timing, warnings | rename `power`→`timing`; `ready`/`bits`/`conversions` are core framing, unchanged |
| `hx711` | ready, bits, conversions, power, warnings | ready, bits, conversions, timing, warnings | same as `hx710a` |
| `ina219` | data, warnings, conformance | data, status, warnings, timing | rename `conformance`→`timing`; CNVR/OVF flags in Bus Voltage read qualify for `status` |
| `ina226` | data, warnings, timing | data, status, warnings, timing | already has `timing`; add `status` for CNVR/OVF (parallel to `ina219`/`ina3221`) |
| `ina3221` | data, warnings, timing | data, status, warnings, timing | same as `ina226` |
| `lps22df` | data, warnings | data, warnings | no conformance checker yet |
| `lps28dfw` | data, warnings | data, warnings | no conformance checker yet |
| `lps33hw` | data, warnings, timing | unchanged | already correct |
| `mcp23017` | data, warnings, timing | unchanged | already correct |
| `mcp4725` | data, warnings, timing | unchanged | already correct |
| `mcp4728` | data, warnings, timing | unchanged | already correct |
| `mfrc522` | data, warnings, timing | unchanged | already correct |
| `mpu6050` | data, warnings, conformance | data, warnings, timing | rename; **also update `conformance/imu/mpu6050_conformance.py`'s exact-match predicates to substring** (Finding 4) |
| `neo6` | sentences, fields, warnings | unchanged (tier-1 sub-rows) | no conformance checker uses named markers (uses `True` "any sentence" predicate) |
| `neopixel` | bits, bytes, resets, warnings | unchanged | `resets` is core framing (Exception above), stays put |
| `pcf8574` | data, warnings, timing | unchanged | already correct |
| `pcf8575` | data, warnings, timing | unchanged | already correct |
| `pcf8576` | data, warnings | data, warnings | checker uses `True` ("any transaction") predicate, no named marker to migrate |
| `pcf8591` | data, warnings | data, warnings | review checker before assuming no promotion is needed |
| `rda5807m` | data, warnings, conformance | data, warnings, timing | rename; **also update `conformance/comms/rda5807m_conformance.py`'s exact-match predicates to substring** (Finding 4) |
| `rfm9x` | data, warnings | data, warnings | no conformance checker yet |
| `sipo` | bytes, latches, clears, disableds | bytes, latches, clears, disableds, warnings | **add missing `warnings` row** |
| `sk6812rgbw` | pixels, warnings | unchanged | timing check reads the underlying `neopixel` decoder's `resets` row, not this decoder — nothing to change here |
| `ws2812b` | pixels, warnings | unchanged | same as `sk6812rgbw` |

### Files outside `sigrok/` to update in the same effort

- `AGENTS.md` — replace the "Annotation conventions" bullet list (currently: "at least two strings … sigrok shows the shortest one that fits") with a reference to this spec's row taxonomy and per-content-kind tier minimums.
- `specs/_template_chip.md` and `specs/_template_chip_io_expander.md` — their `## Sigrok Decoder` template comment already says to name conformance-check annotation pairs; add a line pointing at this spec's row-naming convention (`timing` row, not `conformance`) so new chips don't reintroduce Finding 1.
- `wiki/Sigrok.md` — its **Annotations** section currently says "every decoder in this repo defines two annotation rows" (Data, Warnings); update to describe the four-tier taxonomy and the variable-length convention (including that a narrow PulseView zoom can show a 2–8 character abbreviation, not just a "short form").
- `conformance/imu/mpu6050_conformance.py`, `conformance/comms/rda5807m_conformance.py` — exact-match → substring (Finding 4, required, not optional).
- Any other `conformance/*/*_conformance.py` file whose predicate text changes as a side effect of a decoder's Data/status wording changing (review each one touched, per "Required companion review" above) — in practice this means re-reading every conformance checker for the chip being retrofitted before changing that chip's decoder text, not just the ones explicitly named in this spec.

## Implementation Checklist

- [ ] `AGENTS.md` "Annotation conventions" section rewritten per this spec
- [ ] `specs/_template_chip.md` Sigrok Decoder section references this spec's row convention
- [ ] `specs/_template_chip_io_expander.md` Sigrok Decoder section references this spec's row convention
- [ ] `wiki/Sigrok.md` Annotations section rewritten per this spec
- [ ] Every `sigrok/<x>/pd.py` retrofitted per the per-decoder inventory table above (row renames/merges, tier-3 warning strings, tier-shaped timing markers, `status` rows where the criterion is met, `OUTPUT_PYTHON` registered)
- [ ] `conformance/imu/mpu6050_conformance.py` — exact-match predicates changed to substring containment
- [ ] `conformance/comms/rda5807m_conformance.py` — exact-match predicates changed to substring containment
- [ ] Every conformance checker whose chip's decoder text changed has been re-read and, if needed, its predicates updated to match the new tier-0 text (see "Required companion review")
- [ ] Spot-check: for at least one I²C chip (`ina219`), one SPI chip (`mfrc522`), and one raw-logic chip (`hx711`), replay the committed `.sr` test session (or a fresh capture) through both PulseView (visual tier check at multiple zoom levels) and `sigrok-cli -P ...,--protocol-decoder-samplenum` (confirms tier-0 text still matches every affected checker's predicate)
