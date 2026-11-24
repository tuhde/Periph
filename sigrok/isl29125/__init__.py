"""
ISL29125 digital red, green and blue colour light sensor sigrok protocol decoder.

Stacks on the i2c decoder. Matches the address 0x44. Annotates every register
access by name (DEVICE_ID, CONFIG1, CONFIG2, CONFIG3, LOW_TH, HIGH_TH, STATUS,
GREEN, RED, BLUE); decodes CONFIG1 (mode, range, resolution, sync source),
CONFIG2 (IR compensation), CONFIG3 (interrupt channel, persistence, conversion
interrupt) and STATUS (RGBCF, BOUTF, CONVENF, RGBTHF); assembles the 16-bit
green/red/blue counts and the 16-bit thresholds (LSB first). A read uses the
pointer set by the preceding write (with or without a repeated START) and
auto-increments over a block transfer.

Warnings: a write of a value other than 0x46 to DEVICE_ID (register 0x00 resets
the chip on write), a DEVICE_ID that is not 0x7D, a write to a read-only
register, a read of the data registers without a preceding STATUS read that
showed CONVENF = 1, and a set BOUTF flag. No timing annotation pair is emitted,
since no timing constraint is conformance-checked.
"""

from .pd import Decoder
