"""
AS5600 12-bit contactless rotary position sensor sigrok protocol decoder.

Stacks on the i2c decoder. Decodes all registers: STATUS (MD/ML/MH flags),
ANGLE (12-bit scaled angle), RAW_ANGLE (12-bit unscaled angle), ZMCO (OTP
burn count), ZPOS/MPOS/MANG (position/angle range as 12-bit values and
degrees), CONF (power mode, hysteresis, output stage, PWM frequency, slow
filter, fast filter threshold, watchdog), AGC (automatic gain control),
MAGNITUDE (12-bit CORDIC magnitude), and BURN (OTP burn commands).

Supported address: 0x36 (fixed).
"""

from .pd import Decoder
