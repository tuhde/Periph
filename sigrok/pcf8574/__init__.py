"""
PCF8574 / PCF8574A 8-bit I2C I/O expander sigrok protocol decoder.

Stacks on the i2c decoder. Annotates every read and write transaction
with the hex port byte and the individual pin states (P7–P0).

Supported addresses:
  PCF8574:  0x20–0x27 (A2 A1 A0 pins)
  PCF8574A: 0x38–0x3F (A2 A1 A0 pins)
"""

from .pd import Decoder
