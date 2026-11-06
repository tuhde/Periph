"""
MB85RC256V 256 Kbit (32 KiB) I2C FRAM sigrok protocol decoder.

Stacks on the i2c decoder. Matches I2C addresses 0x50-0x57 (A2:A0 is shown
in the annotation) and the reserved Device ID address 0x7C. Annotates byte
write / sequential write (15-bit address, data bytes), random read /
sequential read, current-address read, and the Device ID sequence (the 3
bytes are decoded into manufacturer, density and product and checked
against 0x00A510). An address with bit 15 set and a sequential access that
wraps past 0x7FFF are flagged as warnings.
"""

from .pd import Decoder
