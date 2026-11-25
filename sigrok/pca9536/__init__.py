"""
PCA9536 4-bit I2C / SMBus I/O expander sigrok protocol decoder.

Stacks on the i2c decoder. Annotates every register write and read with the
register name and value, decoded into 4-pin masks (CONFIG -> IN=/OUT= pin
lists, POLARITY -> INV=, OUTPUT and INPUT as 4-pin level strings). A read uses
the pointer set by the preceding write (repeated-start read) or by the last
written pointer (stand-alone read). A change between consecutive INPUT reads is
shown as a changed-pin mask. Warnings are raised for writes to INPUT (0x00),
command bytes with bits 7:2 set, writes to the unused bits 7:4 with values
other than the reset values (1 for OUTPUT/CONFIG, 0 for POLARITY), and
multi-byte writes or reads (the chip has no auto-increment, so extra bytes just
repeat the same register). No timing annotation pair is emitted.

Supported address: 0x41 only (fixed; the chip has no address pins, interrupt or
reset pin). It has no ID register.
"""

from .pd import Decoder
