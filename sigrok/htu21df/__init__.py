"""
HTU21D-F humidity and temperature sensor (Measurement Specialties) sigrok protocol decoder.

Stacks on the i2c decoder. Matches I2C address 0x40. Annotates every command by
name (temperature and humidity trigger with and without hold master, user
register read/write, soft reset), decodes the 3-byte temperature and humidity
results into degrees Celsius and %RH with a CRC-8 check and the status bit,
decodes the user register (resolution, VDD_LOW, heater) and annotates
measurement polling (a read header whose ACK is missing while the conversion is
running). CRC mismatches, undocumented command bytes and malformed writes are
flagged as warnings.

For the conformance-checked conversion time of specs/humidity/htu21df.md it
emits the named annotation pair conversion_start (at the STOP after a
no-hold-master trigger command, 0xF3 / 0xF5) and conversion_done (at the first
read header the chip ACKs afterwards). A machine-readable copy of every
transaction is mirrored on OUTPUT_PYTHON as ('I2C_READ' | 'I2C_WRITE',
(address, bytes)) or ('REG_READ' | 'REG_WRITE', (command, bytes)).
"""

from .pd import Decoder
