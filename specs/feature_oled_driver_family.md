# Feature: OLED driver family (compatibility, precedence, abstraction)

**Status:** research / design input — no driver exists yet for any chip below except the SH1106 spec (`specs/display/sh1106.md`).
**Scope:** SSD1306, SSD1315, SSD1309, SSD1305, SH1106, SH1107, SSD1327, SSD1331, SSD1351, SSD1357.
**Method:** all ten datasheets were read (text extracted from `datasheets/display/<chip>.pdf`). Caveats are listed under "Confidence".

## Datasheets

| Chip | File | Revision / source |
|------|------|-------------------|
| SSD1306 | `datasheets/display/ssd1306.pdf` | Solomon Systech, via Adafruit CDN |
| SSD1315 | `datasheets/display/ssd1315.pdf` | Rev 1.1, Apr 2017, via cursedhardware.github.io/epd-driver-ic (replaces the Rev 0.10 preview, which had no command table) |
| SSD1309 | `datasheets/display/ssd1309.pdf` | via hpinfotech.ro |
| SSD1305 | `datasheets/display/ssd1305.pdf` | via Adafruit CDN |
| SH1106 | `datasheets/display/sh1106.pdf` | V0.1 preliminary (already present) |
| SH1107 | `datasheets/display/sh1107.pdf` | Sino Wealth, via displayfuture.com |
| SSD1327 | `datasheets/display/ssd1327.pdf` | Rev 1.8, via luma-oled docs |
| SSD1331 | `datasheets/display/ssd1331.pdf` | Rev 1.2, via Adafruit CDN |
| SSD1351 | `datasheets/display/ssd1351.pdf` | Rev 1.5, via Newhaven |
| SSD1357 | `datasheets/display/ssd1357.pdf` | Rev 1.1 (Apr 2019), via Waveshare |

## Chip summary

| Chip | Vendor | RAM / panel | Pixel format | Interfaces | Charge pump | Scroll engine |
|------|--------|-------------|--------------|------------|-------------|---------------|
| SSD1306 | Solomon | 128×64 | 1 bpp, page-packed | I²C, 3/4-wire SPI, parallel | `8Dh` | yes |
| SSD1315 | Solomon | 128×64 | 1 bpp, page-packed | I²C, 3/4-wire SPI, parallel | `8Dh` (7.5/8.5/9.0 V); IREF select `ADh` | yes (+ `2C/2D` content scroll) |
| SSD1309 | Solomon | 128×64 | 1 bpp, page-packed | I²C, SPI, parallel | none (external VCC) | yes (+ `2C/2D` content scroll) |
| SSD1305 | Solomon | 132×64 | 1 bpp, page-packed, area colour | I²C, SPI, parallel | none | yes |
| SH1106 | Sino Wealth | 132×64 | 1 bpp, page-packed | I²C, SPI, parallel | DC-DC `ADh`+`8Ah/8Bh`, `30–33` pump voltage | no |
| SH1107 | Sino Wealth | 128×128 | 1 bpp, page-packed | I²C, SPI, parallel | DC-DC `ADh`+`8Ah/8Bh` | no |
| SSD1327 | Solomon | 128×128 | 4 bpp gray | I²C, SPI, parallel | – | horizontal only |
| SSD1331 | Solomon | 96×64 RGB | 65k colour (RGB565) | SPI, parallel | – | yes + hardware draw engine |
| SSD1351 | Solomon | 128×128 RGB | 65k / 262k colour | SPI, parallel | – | – |
| SSD1357 | Solomon | 128×128 RGB | 65k / 256 colour | SPI, parallel | – | – |

## Command-set families

### Family A — mono page-RAM, SSD1306 dialect (SSD1306, SSD1315, SSD1309, SSD1305)

Shared opcodes: `00–0F`/`10–1F` column nibbles, `20` memory addressing mode, `21` column range, `22` page range, `40–7F` start line, `81` contrast, `A0/A1` segment remap, `A4/A5`, `A6/A7`, `A8` multiplex, `AE/AF`, `B0–B7` page, `C0/C8` COM scan, `D3` offset, `D5` clock, `D9` pre-charge, `DA` COM pins, `DB` VCOMH, `E3` NOP, scroll `26/27/29/2A/2E/2F/A3`.

Deltas against SSD1306:

- **SSD1315** (verified against Rev 1.1 command table): same fundamental commands as the SSD1306 (`20/21/22` modes, `8Dh` charge pump), so an SSD1306 init sequence applies. Differences:
  - `ADh` + `A[5:4]`: external IREF (POR) or internal IREF 19 µA (ISEG max 150 µA) / 30 µA (240 µA).
  - `8Dh` charge-pump data: `14h` 7.5 V (POR value of the voltage field), `94h` 8.5 V, `95h` 9.0 V (SSD1306 only documents `10h`/`14h`).
  - `10–17h` higher column nibble (not `10–1Fh`; 128 columns need only 3 bits).
  - `2C/2D` content scroll (shared with SSD1309), `23h` fade-out/blinking, `D6h` zoom-in (needs `DAh` A[4]=1, i.e. alternative COM config).
  - I²C 7-bit address `0111 10 SA0` = `0x3C/0x3D`; no status read in serial mode.
- **SSD1309:** no charge pump (`8Dh` has no effect; panel needs external VCC); adds `DCh` GPIO, `FDh` command lock (must be unlocked, `FDh 12h`), `2C/2D` content scroll.
- **SSD1305:** 132 columns; adds `82` area-colour brightness, `91` LUT, `92/93` bank colours, `AB` dim mode, `AD` master configuration, `AC` display-on-dim, `D8` area-colour / low-power mode, `E0/EE` read-modify-write; `AA` reserved; no charge pump.

### Family B — mono page-RAM, Sino Wealth dialect (SH1106, SH1107)

Same opcode numbering as Family A for the basics, but:

- DC-DC is `ADh` followed by `8Ah` (off) / `8Bh` (on) — **not** `8Dh`.
- SH1106: page addressing only (**no** `20/21/22`), 132-column RAM (128-wide panels normally start at column 2), `30–33` pump voltage, `E0/EE` read-modify-write, no scroll engine.
- SH1107: 128 commons / 128×128 RAM, `20h` selects page (POR) or vertical addressing, 7-bit display offset (`D3`), no scroll engine.

### Family C — windowed-RAM gray/colour (SSD1327, SSD1331, SSD1351, SSD1357)

Shared: `15` column window, `75` row window, `A0` remap, `A1` start line, `A2` offset, `A4–A7` display mode, `B1` phase length, `B3` clock/oscillator, `B6` second pre-charge, `B8/B9` gray table / linear LUT, `BB/BC` pre-charge voltage, `BE` VCOMH, `FD` command lock.

Per-chip differences:

| | SSD1327 | SSD1331 | SSD1351 | SSD1357 |
|---|---|---|---|---|
| Contrast | `81` (one register) | `81/82/83` per colour + `87` master | `C1` per colour + `C7` master | `C1` + `C7` |
| Multiplex | `A8` | `A8` | `CA` | `CA` |
| Pixel stream | data after window | data after window | `5C` write / `5D` read | `5D` read; write stream (verify) |
| Extras | `26/27/2E/2F` scroll | `21–26` line/rect/copy/dim/clear/fill, `27/2E/2F` scroll, `B0` power save | `B2` display enhancement, `B5` GPIO | `A0` also sets colour depth |

## Compatibility and precedence

Precedence = which chip's behaviour is the reference when a driver supports several.

1. **SSD1306 is the Family A reference.** SSD1309 and SSD1315 are supersets for initialisation purposes (SSD1309 additionally needs the `FDh` unlock; its `8Dh` is a no-op). SSD1305 is a sibling, not a subset — it is only partly SSD1306-compatible.
2. **SH1106 is the Family B reference;** SH1107 extends it (more rows, vertical addressing mode).
3. **SH1106 is not SSD1306-compatible for bulk writes** (no `20/21/22`; page-by-page writes only; column offset).
4. **Opcodes collide across families, so dispatch cannot be by opcode.** `AD` (DC-DC / master config / IREF), `AB` (dim mode / function select), `D5` (clock divide / function selection B), `B0` (page select / power save).
5. **Family C is internally consistent only for the window/remap/lock scaffolding.** Contrast, multiplex and pixel-stream opcodes differ per chip, and the framebuffer format differs (4 bpp / RGB565 / RGB666).
6. **I²C addresses:** SSD1306, SSD1315, SSD1309, SSD1305, SH1106, SH1107 all answer at `0x3C/0x3D` with no identity register, so they cannot be told apart by probing. The `registry/chips.json` entry for each should be `"id_probe": null`, `"probe_safety": "write_sensitive"` (as already set for SH1106). Family C chips here are SPI/parallel in practice (SSD1327 also supports I²C).

## Recommended abstraction

```
DisplayConnection / I²C control-byte transport                  (exists: specs/transport_display.md)
  └─ MonoOled (1-bpp framebuffer, page-packed, blit, contrast, flip, invert)
       ├─ Ssd1306Core  (opcode table, 20/21/22 modes, 8D charge-pump hook)
       │    ├─ Ssd1306
       │    ├─ Ssd1315   = Ssd1306 + IREF select (ADh)
       │    ├─ Ssd1309   = Ssd1306 − charge pump + FDh unlock + DCh
       │    └─ Ssd1305   132 cols + area colour / dim / master-config extras
       └─ Sh110xCore   (page-only blit, ADh/8Bh DC-DC, column offset)
            ├─ Sh1106   132×64
            └─ Sh1107   128×128, adds vertical addressing
  └─ WindowedRamOled (15h/75h window + stream write; per-chip pixel format)
       ├─ Ssd1327 (4-bpp gray)
       ├─ Ssd1331 (RGB565 + draw engine)
       ├─ Ssd1351 (RGB565/666, 5C/5D)
       └─ Ssd1357 (65k/256 colour; verify)
```

Guidelines:

- **Do not** build a single "SSD13xx" class; share the transport, lifecycle and framebuffer, not the command layer.
- Parameterise constant-only differences (`columns`, `rows`, `column_offset`, `has_charge_pump`, `needs_unlock`, `supports_addressing_modes`, `scroll_engine`) rather than subclassing.
- Minimal/Full staging (see `CLAUDE.md`): Minimal = init + framebuffer blit + contrast/flip; Full = scroll engine, addressing modes, dim/area-colour, GPIO, draw engine.
- Specs: one per chip under `specs/display/`, each stating only deltas against its parent in the tree above; a shared mono-OLED family core can reference this document.
- Suggested order: SSD1306 (family base) → SSD1315, SSD1309 → SH1107 (extends the existing SH1106 spec) → colour chips as a separate batch.

## Confidence

- **Verified from datasheet text:** SSD1306, SSD1315, SSD1309, SSD1305, SH1106, SH1107, SSD1327, SSD1331, SSD1351 command lists and RAM geometry.
- **SSD1315:** verified against the Rev 1.1 datasheet (Apr 2017, full command table). The earlier Rev 0.10 preview was replaced. Some appendix footers in Rev 1.1 still read "Rev 1.0", i.e. the document is a point release rather than a fully re-typeset one.
- **SSD1357:** command table text was only partly extractable (`15/75`, `5D`, `A0`, `B1`, `B3`, `B6`, `B8/BC/BD`, `B9`, `BB`, `BE`, `C1`, `C7`, `CA`, `FD`); the `5C` write command and remaining entries should be confirmed from the PDF.
- The "SH1106 vs SSD1306" and "SSD1309 pin/command compatible" statements are cross-checked against third-party sources (zbotic.in, GCBASIC) in addition to the datasheets.
