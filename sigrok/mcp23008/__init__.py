"""
MCP23008 8-bit I2C I/O expander sigrok protocol decoder.

Stacks on the i2c decoder. Annotates every register write and read with the
register name and value, decoded into symbolic field names (IODIR -> IN=/OUT=
pin lists, GPPU -> PU=, IOCON -> SEQOP HAEN ODR INTPOL, GPIO/OLAT/INTF/INTCAP
as 8-pin masks). Sequential transactions are annotated byte by byte using the
roll-over address progression 0x0A -> 0x00, and warnings are raised for writes
to the read-only registers INTF (0x07) and INTCAP (0x08) and for IOCON writes
with the unimplemented bits 7:6 or 0 set.

Supported addresses: 0x20-0x27 (A2 A1 A0 pins). The chip shares these
addresses with the MCP23017 and has no ID register, so it cannot be told apart
on the bus. The SPI variant is the separate `mcp23s08` decoder, which shares
this register table.
"""

from .pd import Decoder
