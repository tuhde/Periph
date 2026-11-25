"""
PCA9541 2-to-1 I2C-bus master selector sigrok protocol decoder.

Stacks on the i2c decoder. Matches the addresses 0x70-0x7F (A3..A0). Annotates
each access by register name (IE, CONTROL, ISTAT) with command-code decoding
(AI flag, register number; invalid command codes and codes with unused bits set
are flagged); decodes IE into mask flags, CONTROL into MYBUS / NMYBUS / BUSON /
NBUSON with the derived state ("has control, bus on" etc.) plus BUSINIT, TESTON
and NTESTON, and ISTAT into the interrupt flags. Auto-increment is handled: each
byte of a multi-byte read or write is named by its own register (including the
read wrap-around ISTAT -> IE). A CONTROL write is annotated with the action it
implies (take / give control, bus on / off, init requested) by comparing its
BUSON / MYBUS bits with the preceding CONTROL read.

Warnings: a write to ISTAT (not acknowledged) and a command code with unused bits
set. No timing annotation pair is emitted, since no constraint is conformance-checked.
"""

from .pd import Decoder
