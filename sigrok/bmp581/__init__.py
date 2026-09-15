"""
BMP581 sigrok protocol decoder.

Decodes BMP581 I²C register transactions including chip-ID verification,
configuration register writes (OSR_CONFIG, ODR_CONFIG, DSP_CONFIG/IIR,
INT_CONFIG, INT_SOURCE, FIFO_SEL, FIFO_CONFIG, CMD), and data reads
(pressure / temperature / combined burst from 0x1D–0x22).
"""

from .pd import Decoder

__all__ = ["Decoder"]