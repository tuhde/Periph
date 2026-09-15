"""
RFM9x sigrok protocol decoder.

Decodes RFM9x (RFM95/96/97/98W) SPI transactions into register-level reads
and writes, with FIFO bursts (register 0x00) grouped as payload bytes and
``RegOpMode`` / ``RegPaConfig`` writes decoded into recognised LoRa mode
strings.

Stacks on top of the ``spi`` protocol decoder. It expects data in the form::

    ptype == 'CS_ASSERT' / 'CS_DEASSERT':     cs edge
    ptype == 'DATA':                         (mosi, miso)

Where ``mosi`` and ``miso`` are single bytes. The decoder reassembles
register-pointer and data bytes from MOSI, and annotates the corresponding
MISO read-back.
"""

from .pd import Decoder
