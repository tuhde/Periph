"""
AD7705 sigrok protocol decoder.

Decodes AD7705 2-channel, 16-bit sigma-delta ADC SPI transactions into
register-level reads and writes. Stacks on the `spi` decoder.
"""

from .pd import Decoder
