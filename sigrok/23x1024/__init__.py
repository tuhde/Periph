"""
23X1024 sigrok protocol decoder.

Decodes Microchip 23LC1024 / 23A1024 1-Mbit serial SRAM SPI transactions into
chip-level READ, WRITE, RDMR, WRMR and RSTIO operations.
"""

from .pd import Decoder
