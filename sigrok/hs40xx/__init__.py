"""
HS40xx humidity and temperature sensor (Renesas HS4001 / HS4003) sigrok protocol decoder.

Stacks on the i2c decoder. Matches I2C address 0x54. Commands are single bytes after the
write address: measurement commands 0xE3 / 0xF3 (temperature, hold / no-hold) and 0xE5 / 0xF5
(humidity + temperature), register read 0xA7 and write 0xA6, stop-periodic 0x30, sensor ID
0xD7 and soft reset 0xFE. The decoder annotates every command by name, register reads and
writes with the decoded resolution, periodic, alert-enable and threshold fields, and decodes
result frames (3 bytes temperature only, 5 bytes humidity + temperature, 6 bytes with the
leading alert status byte in periodic mode) into %RH and degrees Celsius with the CRC-8
(polynomial 0x1D, initial value 0xFF) verified. It also decodes the 4-byte ID frame.

Warnings: CRC mismatch, a no-hold read NACKed because the data are not ready, and commands
other than the data fetch / 0x30 while periodic mode is active (tracked from register
writes; this is state, not a timing pair). No conformance timing pair is emitted: the HS40xx
datasheet gives only typical conversion times (see specs/humidity/hs40xx_timing.conf). A
machine-readable copy of every transaction is mirrored on OUTPUT_PYTHON as ('I2C_READ' |
'I2C_WRITE', (address, bytes)) or ('REG_READ' | 'REG_WRITE', (register, bytes)).
"""

from .pd import Decoder
