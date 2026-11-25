"""
SD card (SPI mode) sigrok protocol decoder.

Decodes SD / SDHC / SDXC / microSD card traffic in SPI mode into commands
(CMD0, CMD8, ACMD41, CMD17/18/24/25, ...), R1/R3/R7 responses, data blocks
with CRC16 checking and the CSD / CID / OCR registers.
"""

from .pd import Decoder
