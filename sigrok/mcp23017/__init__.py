"""
MCP23017 16-bit I2C I/O expander sigrok protocol decoder.

Stacks on the i2c decoder. Annotates every register write and read with
the register name and value, decoded into symbolic field names.

Supported addresses: 0x20–0x27 (A2 A1 A0 pins).
IOCON.BANK is assumed to be 0 (power-on default); addresses below reflect
the BANK=0 interleaved register map.
"""

from .pd import Decoder