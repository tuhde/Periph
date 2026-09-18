"""
APA102 sigrok protocol decoder.

Decodes APA102 synchronous SPI frame into pixel data with hardware brightness.
Stacks on the spi transport decoder.
"""

from .pd import Decoder