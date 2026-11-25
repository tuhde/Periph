"""
AT24C02N 2 Kbit (256 B) I2C EEPROM sigrok protocol decoder.

Stacks on the i2c decoder. Matches I2C addresses 0x50-0x57 (device-select byte
1010 A2 A1 A0) and the single memory address byte. Annotates byte and
page writes (address, data), random, sequential and current-address reads, and
ACK polling (a device-select byte whose ACK is missing while the write cycle is
running). Writes that cross a 8-byte page boundary (the address wraps inside
the page) and accesses above 0x00FF are flagged as warnings.

For the conformance-checked write cycle time of specs/memory/at24c02n.md it
emits the named annotation pair write_cycle_start (at the STOP of a data write)
and write_cycle_done (at the first device-select byte the chip ACKs
afterwards). A machine-readable copy of every transaction is mirrored on
OUTPUT_PYTHON as ('REG_READ' | 'REG_WRITE', (register, bytes)).
"""

from .pd import Decoder
