# Periph I²C discovery registry

`chips.json` lists every I²C chip the library knows: the addresses it can answer on, the identity register that tells it apart from its neighbours (if it has one), and whether a stray write is safe. `discover()` in the host languages (Python, C++, Node.js, Rust, Go, Java) is driven entirely by this data. Design: `specs/feature_i2c_discovery.md`.

The file is deliberately **pure data** — nothing here references the language trees — so this directory can be split into its own repository and fed by community issues.

## Files

| File | Purpose |
|---|---|
| `chips.json` | The registry (source of truth) |
| `schema.json` | JSON Schema for `chips.json` (for external tools and editors) |
| `known_ambiguities.json` | Chip pairs whose identity registers can read the same value at an overlapping address — each pair is a conscious, reviewed decision |
| `scripts/validate.js` | Structure and semantic checks; `--check` for CI |
| `scripts/validate_test.js` | Tests for the validator |
| `scripts/generate.js` | Emits the native table for each language; `--check` fails when one is stale; `--registry <path>` reads another `chips.json` |

## Adding a chip

1. Add an entry to `chips.json`. Every value must come from the datasheet (put its path or URL in `datasheet`):

   ```json
   {
     "id": "bme280",
     "name": "BME280",
     "category": "environmental",
     "addresses": ["0x76", "0x77"],
     "id_probe": {
       "register": "0xD0", "reg_bytes": 1, "length": 1,
       "byte_order": "big", "mask": "0xFF", "expected": ["0x60"]
     },
     "probe_safety": "register_pointer",
     "driver": "bme280",
     "datasheet": "datasheets/environmental/bme280.pdf"
   }
   ```

   - `addresses`: `"0xNN"` or an inclusive range `"0xNN..0xMM"`, within `0x08`–`0x77`.
   - `id_probe`: `null` when the chip has no identity register. `register` is the byte(s) **as sent on the wire** (include command bits such as APDS-9930's `0x80`). `mask` is applied to the value read; `expected` lists the accepted masked values.
   - `probe_safety`: `register_pointer` if a one-byte write only selects a register; `write_sensitive` if the chip treats a write as data or a command (port expanders, DACs, command-driven sensors). When in doubt, use `write_sensitive`.
   - `aliased`: `true` only if the chip answers on *every* address in its range regardless of pins (24AA02UID).
   - `driver`: omit or `null` for chips without a driver in this library. New chips are added with `null` during spec prep and set to the driver name when the driver is implemented.

2. Run `node registry/scripts/validate.js`. If it reports a collision, check the identity registers; if two chips genuinely cannot be told apart, add the pair to `known_ambiguities.json`.
3. Run `node registry/scripts/generate.js` and commit the regenerated tables.
