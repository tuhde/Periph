"""
PCA9539 16-bit I2C / SMBus I/O expander sigrok protocol decoder.

Stacks on the i2c decoder. Annotates every register write and read with the
register name and value, decoded into pin masks (CONFIG0/1 -> IN=/OUT= pin
lists, POLARITY0/1 -> INV=, OUTPUT0/1 and INPUT0/1 as 8-pin level strings).
The eight registers form four pairs, and a multi-byte transfer alternates
between the two registers of a pair, so every byte is named by its own
register (a two-byte transfer starting at a port-0 register covers both ports
and is also shown as one combined 16-bit value). A read uses the pointer set by
the preceding write (repeated-start read) or by the last written pointer
(stand-alone read). A change between consecutive INPUT reads is shown as a
changed-pin mask. Warnings are raised for writes to INPUT0/1 (0x00/0x01) and
pointer values above 0x07, and for another device being read while the stored pointer is 0x00 after an INPUT read (interrupt errata hint).

Supported addresses: 0x74–0x77 (A1, A0). The chip shares
these addresses with the TCA9539 (register-compatible) and has no ID
register, so it cannot be told apart on the bus.
"""

from .pd import Decoder
