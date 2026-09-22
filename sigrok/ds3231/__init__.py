"""
DS3231 extremely accurate I2C RTC/TCXO/crystal sigrok protocol decoder.

Stacks on the i2c decoder. Tracks the auto-incrementing register pointer
(0x00-0x12, wraps 0x12->0x00) across multi-byte read/write transactions and
decodes each byte against its register: BCD time/date (0x00-0x06), the two
alarm register blocks with mask-bit/DY-DT decode (0x07-0x0D), the CONTROL
register's bit fields (0x0E), CONTROL_STATUS's OSF/EN32kHz/BSY/A2F/A1F flags
(0x0F, emitted to a dedicated status row), the aging-offset trim code
(0x10), and the two-byte temperature reading converted to degrees Celsius
(0x11-0x12).

Also emits the temp_conversion_ready timing-marker pair used by
conformance/rtc/ds3231_conformance.py (see specs/rtc/ds3231.md, "Sigrok
Decoder" and "Timing Constraints", and specs/rtc/ds3231_timing.conf):
temp_conversion_ready_start when CONTROL's CONV bit (0x0E bit 5) is written
1, temp_conversion_ready_done at the next CONTROL_STATUS read (0x0F) where
BSY (bit 2) reads 0.

Supported address: 0x68 (fixed).
"""

from .pd import Decoder
