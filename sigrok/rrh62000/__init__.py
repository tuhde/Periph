"""
RRH62000 all-in-one air-quality module sigrok protocol decoder.

Stacks on the i2c decoder and matches the fixed address 0x69. The module is register addressed:
a write of one command byte sets the pointer (optionally followed by the data byte of a
writable command) and a read returns that command's data (specs/gas/rrh62000.md). Annotated:

  - every command by name (READ, DATA, SLEEP, CLEAN, RESET, MAVE, TINTC_H/L, TCLEAN, SPEEDFAN,
    RMOX, UID, ARGVER, CSTATUS, FWVER) with the written value decoded (sleep/wake, moving
    average, cleaning interval bytes, cleaning time, fan speed)
  - the 37-byte measurement block: status flags, the five number-concentration bins, PM1/2.5/10
    for the KCl and cigarette-smoke references, temperature, humidity, TVOC, eCO2 and IAQ, with
    the CRC-8 checked (polynomial 0x31, init 0xFF)
  - DATA, MOX resistance, unique ID, algorithm and firmware versions and the cleaning status

Warnings are raised for a CRC error, an all-0xFF (start-up) block, two accesses less than 1 s
apart (tRD, needs the sample rate), out-of-range settings and RESET/CLEAN writes. The RRH62000
has no bus-checked timing constraint with a datasheet start/end pair, so no conformance
annotation pair is emitted (see specs/gas/rrh62000_timing.conf). OUTPUT_PYTHON emits
('WRITE', (command, value or None)), ('READ', (command, bytes)) and ('MEASUREMENT', dict).
"""

from .pd import Decoder
