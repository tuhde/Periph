"""
SSD1306 4-wire SPI sigrok protocol decoder.

Stacks on the `display_spi` decoder (CS, D/C, optional RES) and decodes the SSD1306 command set and
display RAM data exactly like the I2C decoder `ssd1306` (shared command table, see sigrok/ssd1306/pd.py):
every byte sent with D/C low is a command byte (arguments of double-byte commands included), every
byte sent with D/C high is display RAM data. SPI cannot be read, so there are no read annotations.
"""

from .pd import Decoder
