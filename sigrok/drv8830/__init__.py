"""
DRV8830 low-voltage motor driver with I2C interface sigrok protocol decoder.

Stacks on the i2c decoder. Recognizes any address in 0x60-0x68 (the nine
A0/A1 strap combinations). Each transaction addresses exactly one register
(no auto-increment): a sub-address byte followed by one data byte (write),
or a sub-address byte, repeated START and a read.

Annotates CONTROL (0x00) reads/writes on the data row as the VSET code
converted to volts (datasheet Table 1: VREF * VSET / 16, VREF = 1.285 V),
IN1/IN2 and the resulting H-bridge function (Forward/Reverse/Brake/Standby),
and FAULT (0x01) reads/writes on the status row as FAULT/OCP/UVLO/OTS/ILIMIT
plus CLEAR writes. Warns on a Forward/Reverse CONTROL write using a reserved
VSET code (0x00-0x05), a write to an address outside 0x60-0x68, and writes
carrying more than one data byte.

Also emits the register_write timing-marker pair used by
conformance/motor/drv8830_conformance.py (see specs/motor/drv8830.md,
"Sigrok Decoder" and "Timing Constraints", and
specs/motor/drv8830_timing.conf): register_write_start on the ADDRESS WRITE
of any CONTROL or FAULT write transaction, register_write_done on its STOP.
"""

from .pd import Decoder
