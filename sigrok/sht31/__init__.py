"""
SHT31 temperature and humidity sensor (Sensirion) sigrok protocol decoder.

Stacks on the i2c decoder. Matches I2C addresses 0x44 and 0x45. Annotates the
16-bit commands by name (single-shot variants with and without clock stretching,
periodic rates, ART, fetch data, break, heater, status, soft reset), decodes the
6-byte result into degrees Celsius and %RH with a per-word CRC-8 check, decodes
the status register bits and annotates measurement polling (a read header whose
ACK is missing while the conversion is running). CRC mismatches and unknown
commands are flagged as warnings.

For the conformance-checked single-shot conversion time of specs/humidity/sht31.md
it emits the named annotation pair conversion_start (at the STOP after a
single-shot command) and conversion_done (at the first read header the chip ACKs
afterwards). A machine-readable copy of every transaction is mirrored on
OUTPUT_PYTHON as ('I2C_READ' | 'I2C_WRITE', (address, bytes)) or
('REG_READ' | 'REG_WRITE', (command, bytes)).
"""

from .pd import Decoder
