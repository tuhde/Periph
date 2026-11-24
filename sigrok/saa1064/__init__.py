"""
SAA1064 4-digit LED driver — sigrok protocol decoder.

Stacks on the i2c decoder (addresses 0x38-0x3B). The first byte of each write
is the instruction (subaddress) byte; the decoder annotates:
  - the subaddress (0x00 control, 0x01-0x04 digits) and auto-increment
  - the control byte: dynamic/static mode, digit-pair blanking, segment test and
    segment current (3/6/12 mA bits summed in mA)
  - each digit byte (hex, lit segments, decimal point and, for the default
    mapping, the displayed character)
  - the status byte read (PR power-reset flag)
Warnings: subaddress above 4, control byte with bit 7 set, data past digit 4.
"""

from .pd import Decoder
