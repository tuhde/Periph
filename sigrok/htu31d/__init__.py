"""
HTU31D humidity and temperature sensor sigrok protocol decoder.

Stacks on the i2c decoder and matches I2C addresses 0x40 and 0x41. The chip has
no register pointer: every write is a single command byte. Annotates, from
specs/humidity/htu31d.md:

  - commands: reset (0x1E), heater on/off (0x04 / 0x02), conversion (0x40-0x5F,
    with the decoded OSR pair), read T&RH (0x00), read RH (0x10), read
    diagnostic (0x08) and read serial number (0x0A)
  - results: 16-bit temperature and humidity words with CRC-8 check, decoded as
    degrees C and %RH
  - the diagnostic byte as named flags
  - the serial number with its CRC

It warns on undocumented command bytes and CRC mismatches. No conformance
annotation pair is emitted (no checked timing constraint).
"""

from .pd import Decoder
