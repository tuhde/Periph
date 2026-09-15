"""
MCP2515 sigrok protocol decoder.

Decodes Microchip MCP2515 stand-alone CAN 2.0B controller SPI transactions
into chip-level register reads, writes, and the special instruction bytes
(RESET, RTS, READ STATUS, RX STATUS, BIT MODIFY, LOAD TX BUFFER, READ RX
BUFFER). The decoder reassembles CAN identifiers from SIDH/SIDL/EID8/EID0
for both TX (LOAD TX BUFFER) and RX (READ RX BUFFER) operations and decodes
the READ STATUS and RX STATUS flag bits.

Decoder id: ``mcp2515``
Input:      ``spi`` (sits on top of the sigrok ``spi`` decoder)
Outputs:    ``mcp2515``
"""

from .pd import Decoder

__all__ = ['Decoder']
