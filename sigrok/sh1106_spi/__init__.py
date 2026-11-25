"""
SH1106 4-wire SPI sigrok protocol decoder.

Stacks on the `display_spi` decoder (CS, D/C = A0, optional RES) and decodes the SH1106 command set and
display RAM data exactly like the I2C decoder `sh1106` (shared command table, see sigrok/sh1106/pd.py):
every byte sent with D/C low is a command byte (arguments of double-byte commands included), every
byte sent with D/C high is display RAM data. SPI cannot be read, so there are no read annotations.
"""

from .pd import Decoder
