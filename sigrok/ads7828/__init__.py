"""
ADS7828 12-bit 8-channel I2C ADC sigrok protocol decoder.

Stacks on the i2c decoder. The ADS7828 has no registers: every conversion is
started by a one-byte command (write) which is normally followed by a
repeated START and a 2-byte read of the 12-bit result. The decoder annotates
each command byte (input mode SE/DIFF, channel or differential pair with
polarity, power-down mode, a warning if the unused X bits are set) and each
2-byte result (12-bit code and the voltage at the 2.5 V internal reference,
with the channel taken from the last command). A read without a preceding
command in the capture is flagged as stale data. No timing annotation pair
is emitted (the reference-settling time has no tabulated limit).

Supported addresses: 0x48 (A1=A0=GND) through 0x4B (A1=A0=VDD).
"""

from .pd import Decoder
