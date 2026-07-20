"""
AHT21 temperature and humidity sensor sigrok protocol decoder.

Stacks on the i2c decoder. Decodes command-based transactions: Trigger
Measurement (0xAC 0x33 0x00), Soft Reset (0xBA), Calibration Init
(0x1B/0x1C/0x1E 0x00 0x00), Status reads (BUSY/CAL bits), and
Measurement data reads (6 or 7 bytes with temperature and humidity).

Supported address: 0x38 (fixed).
"""

from .pd import Decoder
