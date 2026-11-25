"""
SHTC3 humidity and temperature sensor sigrok protocol decoder.

Stacks on the i2c decoder and matches I2C address 0x70. The chip has no register
pointer: every write is a 16-bit command, MSB first. Annotates, from
specs/humidity/shtc3.md:

  - commands: wake-up (0x3517), sleep (0xB098), soft reset (0x805D), read ID
    (0xEFC8) and the eight measurement commands with their mode (normal / low
    power), clock stretching and first value (T or RH)
  - results: temperature and humidity words (in the order of the last measurement
    command) with CRC-8 check, decoded as degrees C and %RH
  - the ID word with the SHTC3 product code check (0x083F / 0x0807) and its CRC

It warns on CRC mismatches, on a command sent while the sensor is asleep (no
wake-up seen), on unknown command codes and on an unexpected chip ID. No
conformance annotation pair is emitted (no checked timing constraint).
"""

from .pd import Decoder
