"""
PCF8523 low-power I2C real-time clock and calendar sigrok protocol decoder.

Transport input: stacks on the i2c decoder; a single-byte register pointer
follows the address byte on writes, and reads continue from the last pointer
(repeated START is not allowed on this device - every transaction ends with
a STOP).

Supported address: 0x68 (fixed).

Tracks the auto-incrementing register pointer (0x00-0x13, wraps 0x13->0x00)
across multi-byte transactions and annotates each byte against its register:
CONTROL_1/CONTROL_2/CONTROL_3 bit fields and flags on the status row;
BCD time/date (0x03-0x09, including the OS oscillator-stop flag), the four
alarm registers with active-low AEN_x enable decode (0x0A-0x0D), the OFFSET
two's-complement value and correction mode (0x0E), and the timer/CLKOUT
control fields and countdown values (0x0F-0x13) on the data row.

Warnings: access to undefined register addresses (0x14+), a write selecting
the not-allowed PM[2:0]=110, writes of 1 to the read-only WTAF/BLF bit
positions, and repeated START conditions.

Also emits the time_access timing-marker pair used by
conformance/rtc/pcf8523_conformance.py (see specs/rtc/pcf8523.md, "Sigrok
Decoder" and "Timing Constraints", and specs/rtc/pcf8523_timing.conf):
time_access_start at the START of any transaction whose first accessed
register falls in 0x03-0x09, time_access_done at that transaction's STOP.
"""

from .pd import Decoder
