"""
24AA02UID 2K I2C EEPROM with 32-bit unique serial number sigrok protocol decoder.

Stacks on the i2c decoder. Decodes byte writes, page writes, random
reads, sequential reads, and the upper (read-only) block containing
the manufacturer code (0xFA), device code (0xFB), and 32-bit unique
serial number (0xFC-0xFF). Writes to 0x80-0xFF are flagged as
write-protected.

Supported address range: 0x50-0x57 (the 24AA02UID ignores the A0/A1/A2
address pins; the canonical address is 0x50).
"""

from .pd import Decoder
