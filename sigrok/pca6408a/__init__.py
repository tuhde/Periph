"""
PCA6408A 8-bit I2C / SMBus I/O expander sigrok protocol decoder.

Stacks on the i2c decoder. Annotates every register write and read with the
register name and value, decoded into pin masks (CONFIG -> IN=/OUT= pin lists,
POLARITY -> INV=, OUTPUT and INPUT as 8-pin level strings). A read uses the
pointer set by the preceding write (repeated-start read) or by the last
written pointer (stand-alone read). A change between consecutive INPUT reads is
shown as a changed-pin mask. Warnings are raised for writes to INPUT (0x00),
pointer values above 0x03, and multi-byte writes or reads (the chip has no
auto-increment, so extra bytes just repeat the same register).

Supported addresses: 0x20 (ADDR = VSS) and 0x21 (ADDR = VDD(P)). The chip shares
these addresses with the MCP23008/MCP23017/PCF8574 and has no ID register, so it
cannot be told apart on the bus.
"""

from .pd import Decoder
