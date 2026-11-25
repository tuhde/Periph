"""
AHT20 temperature and humidity sensor sigrok protocol decoder.

Stacks on the i2c decoder. Decodes command-based transactions: Trigger
Measurement (0xAC 0x33 0x00), Read Status (0x71), Soft Reset (0xBA),
calibration init (0x1B / 0x1C / 0x1E), Status reads (BUSY/CAL bits), and
6/7-byte measurement data reads (humidity and temperature; the CRC-8 is
verified when the 7th byte is present). Emits the `measurement_start` /
`measurement_done` timing annotations.

Supported address: 0x38.
"""

from .pd import Decoder
