"""
AHT10 temperature and humidity sensor sigrok protocol decoder.

Stacks on the i2c decoder. Decodes command-based transactions: Trigger
Measurement (0xAC 0x33 0x00), Init (0xE1 0x08 0x00), Soft Reset (0xBA),
Status reads (BUSY/MODE/CAL bits), and 6-byte measurement data reads
(humidity and temperature; the AHT10 has no CRC byte). Emits the
`measurement_start` / `measurement_done` timing annotations.

Supported address: 0x38.
"""

from .pd import Decoder
