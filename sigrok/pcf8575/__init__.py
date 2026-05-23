"""
PCF8575 16-bit I2C I/O expander sigrok protocol decoder.

Stacks on the i2c decoder. Annotates every read and write transaction
with both port bytes in hex and the individual pin states (P17–P10 for
Port 1 and P07–P00 for Port 0). A warning annotation is emitted for
transactions that do not consist of exactly 2 data bytes.

Supported addresses:
  PCF8575:  0x20–0x27 (A2 A1 A0 pins)
"""

from .pd import Decoder