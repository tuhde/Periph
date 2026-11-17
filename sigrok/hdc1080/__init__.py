"""
HDC1080 humidity and temperature sensor (Texas Instruments) sigrok protocol decoder.

Stacks on the i2c decoder. Matches I2C address 0x40. Annotates every
register pointer write (Temperature, Humidity, Configuration, Serial ID, Manufacturer/Device ID; pointers
0x00/0x01 also trigger a measurement), decodes configuration words by field
(RST, HEAT, MODE, BTST, TRES, HRES), converts temperature and humidity words to
degrees Celsius and %RH (a 4-byte read is temperature then humidity) and
annotates serial-ID words and verifies the manufacturer ID (0x5449) and device ID (0x1050). Warns on read headers NACKed while the data are not
ready, on data written to read-only registers and on reserved configuration
bits. No timing annotation pair is emitted (no conformance-checked constraints).
A machine-readable copy of every transaction is mirrored on OUTPUT_PYTHON as
('I2C_READ' | 'I2C_WRITE' | 'REG_READ' | 'REG_WRITE', ...).
"""

from .pd import Decoder
