"""
PCA9685 16-channel, 12-bit PWM LED/servo controller sigrok protocol decoder.

Stacks on the i2c decoder (addresses 0x40-0x77, i.e. the chip addresses plus the All Call address
0x70 and the sub-call addresses, and the general call 0x00 for the Software Reset). Annotates every
register access with the register name: MODE1 / MODE2 bit fields, SUBADR1-3 / ALLCALLADR, the four
ON/OFF registers of each channel (with ON/OFF counts, duty percentage and the full ON / full OFF
flags), the ALL_LED registers, and PRE_SCALE with the resulting PWM frequency (25 MHz oscillator
assumed; the 'osc_hz' option overrides it). Warns on ON = OFF, a PRE_SCALE write while the chip is
awake, a PWM write within 500 us after wake-up and a RESTART write within 500 us of clearing SLEEP
(the two timing warnings need the sample rate). The decoded register accesses are also mirrored to
the Python output. No timing annotation pair is emitted (no checked timing constraint).
"""

from .pd import Decoder
