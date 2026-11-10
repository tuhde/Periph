"""
PCF85063A low-power I2C real-time clock and calendar sigrok protocol decoder.

Transport input: stacks on the i2c decoder; a single-byte register pointer
follows the address byte on writes, and reads continue from the last pointer
(repeated START is not allowed on this device - every transaction ends with
a STOP).

Supported address: 0x51 (fixed).

Tracks the auto-incrementing register pointer (0x00-0x11, wraps 0x11->0x00)
across multi-byte transactions and annotates each byte against its register:
Control_1/Control_2/Timer_mode bit fields and flags on the status row;
BCD time/date (0x04-0x0A, including the OS oscillator-stop flag), the five
alarm registers with active-low AEN_x enable decode (0x0B-0x0F), the Offset
two's-complement value in LSB and ppm for the current mode (0x02), the RAM
byte (0x03) and the countdown Timer_value (0x10) on the data row.

Warnings: access to undefined register addresses (0x12+; a read past 0x11
wraps), a software-reset write (Control_1 = 0x58, noted as "resets time"),
a 12-hour mode write, and repeated START conditions.

Also emits the time_access timing-marker pair used by
conformance/rtc/pcf85063a_conformance.py (see specs/rtc/pcf85063a.md,
"Sigrok Decoder" and "Timing Constraints", and
specs/rtc/pcf85063a_timing.conf): time_access_start at the START of any
transaction whose accessed register range includes an address in 0x04-0x0A,
time_access_done at that transaction's STOP.
"""

from .pd import Decoder
