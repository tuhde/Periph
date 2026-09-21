"""
ADXL362 sigrok protocol decoder.

Decodes Analog Devices ADXL362 ultralow-power 3-axis MEMS accelerometer SPI
transactions into chip-level register reads, writes, FIFO reads, and
soft-reset.
"""

from .pd import Decoder