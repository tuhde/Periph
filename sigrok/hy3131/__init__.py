"""
HY3131 sigrok protocol decoder.

Decodes HYCON HY3131 DMM analog front end SPI transactions. Stacks on the `spi`
decoder (CS, MOSI and MISO channels required; Mode 0). Handles the chip's
non-standard framing: command byte `(addr << 1) | R/W`, one blank turnaround
clock on reads (the MISO bit stream is shifted left by one), address
auto-increment, and annotates AD1/AD2/LPF/RMS/peak/counter values, INTF/INTE
flags and the configuration registers.
"""

from .pd import Decoder
