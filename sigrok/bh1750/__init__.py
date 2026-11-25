"""
BH1750FVI ambient light sensor sigrok protocol decoder.

Stacks on the i2c decoder and matches I2C addresses 0x23 and 0x5C. The chip has
no register pointer: every write is a single opcode byte. Annotates, from
specs/light/bh1750.md:

  - opcodes: power down (0x00), power on (0x01), reset (0x07), continuous
    H / H2 / L modes (0x10 / 0x11 / 0x13), one-time H / H2 / L modes
    (0x20 / 0x21 / 0x23) and the measurement-time register pair (high bits
    0x40-0x47, low bits 0x60-0x7F) with the assembled MTreg value
  - results: the 2-byte read decoded to the raw count and the illuminance in
    lux, using the last seen measurement mode and MTreg

It warns on undocumented opcodes, a reset in the power-down state (the chip
ignores it), an MTreg outside 31-254, extra bytes in one write transaction and
a read that starts before the typical measurement time after the measurement
opcode. No conformance annotation pair is emitted (no checked timing
constraint).
"""

from .pd import Decoder
