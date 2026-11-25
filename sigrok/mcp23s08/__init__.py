"""
MCP23S08 8-bit SPI I/O expander sigrok protocol decoder.

Stacks on the spi decoder. Matches opcodes 0x40-0x47 (0100 A1 A0 R/W, the
hardware address bits and the read bit) followed by a register address byte,
then data. Annotates every register read/write with its name and value decoded
into symbolic fields, using the register table shared with the `mcp23008`
(I2C) decoder; sequential transactions are annotated byte by byte with the
roll-over address progression 0x0A -> 0x00. Warnings are raised for writes to
the read-only registers INTF (0x07) / INTCAP (0x08) and for IOCON writes with
bits 7:6 or 0 set.
"""

from .pd import Decoder
