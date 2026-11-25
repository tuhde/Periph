"""
Si7021 humidity and temperature sensor (Silicon Labs) sigrok protocol decoder.

Stacks on the i2c decoder. Matches I2C address 0x40. Annotates every command by
name (RH and temperature measurement with and without hold master, temperature
of the previous RH measurement, software reset, user register read/write,
electronic ID, firmware revision), decodes the 3-byte RH and temperature results
into %RH and degrees Celsius with a CRC-8 check, decodes USER_REG_1 (resolution,
VDD status, heater) and the electronic ID with the device type, and annotates
measurement polling (a read header whose ACK is missing while the conversion is
running). CRC mismatches, undocumented command bytes and malformed writes are
flagged as warnings.

For the conformance-checked RH conversion time of specs/humidity/si7021.md it
emits the named annotation pair conversion_start (at the STOP after a
no-hold-master measurement command, 0xF5) and conversion_done (at the first read
header the chip ACKs afterwards). A machine-readable copy of every transaction is
mirrored on OUTPUT_PYTHON as ('I2C_READ' | 'I2C_WRITE', (address, bytes)) or
('REG_READ' | 'REG_WRITE', (command, bytes)).
"""

from .pd import Decoder
