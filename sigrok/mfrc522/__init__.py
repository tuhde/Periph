"""
MFRC522 sigrok protocol decoder.

Decodes MFRC522 SPI transactions into register-level reads and writes,
with FIFO bursts (register 0x09) grouped and decoded as recognized
ISO/IEC 14443-3 or MIFARE command frames where the leading byte matches a
known command. ``CommandReg`` writes (register 0x01) are separately annotated
with the MFRC522 command name.

Stacks on top of the ``spi`` protocol decoder.
"""

from .pd import Decoder
