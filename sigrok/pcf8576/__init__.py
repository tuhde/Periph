"""
PCF8576 40x4 universal LCD segment driver sigrok protocol decoder.

Stacks on the i2c decoder. The PCF8576 is a write-only device that uses a
command-stream protocol: each I2C write transaction contains one or more
command bytes (with bit 7 = C the continuation flag) followed by zero or
more display data bytes.

The decoder annotates:
  - mode-set        command (0x40 | flags) → E, B, M
  - load-data-pointer command (0x00 | addr) → RAM address 0-39
  - device-select   command (0x60 | subaddress) → 0-7
  - bank-select     command (0x78 | flags) → input/output bank
  - blink-select    command (0x70 | flags) → blink frequency, alternate bank
  - display data bytes following the last command

Supports both I2C addresses: 0x38 (SA0 = VSS) and 0x39 (SA0 = VDD).
"""

from .pd import Decoder
