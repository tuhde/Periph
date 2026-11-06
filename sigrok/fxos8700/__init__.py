"""
FXOS8700CQ 6-axis e-compass (accelerometer + magnetometer) sigrok protocol decoder.

Stacks on the i2c decoder and decodes the single slave of the chip, which sits
at 0x1C, 0x1D, 0x1E or 0x1F depending on SA1 / SA0. Every register of the
Register Map (0x00-0x78) is annotated by name. STATUS / F_STATUS, SYSMOD,
INT_SOURCE, XYZ_DATA_CFG, HP_FILTER_CUTOFF, PL_STATUS, PL_CFG, PL_BF_ZCOMP,
PL_THS_REG, A_FFMT_CFG / SRC, TRANSIENT_CFG / SRC, PULSE_CFG / SRC,
CTRL_REG1-CTRL_REG5, F_SETUP, TRIG_CFG, M_DR_STATUS, M_CTRL_REG1-M_CTRL_REG3
and M_INT_SRC are decoded into named flags; dr, fs, mods, m_os, m_hms and
f_mode are decoded into human-readable values. The burst read of 0x01-0x06 is
assembled into 14-bit signed values converted to g at the last-seen range
(8-bit when f_read is set), the burst of 0x33-0x38 into 16-bit values
converted to uT, a 12-byte burst from 0x01 into both, and TEMP into degrees
Celsius. WHO_AM_I reads are checked against 0xC7.

For the first-data-ready Timing Constraint flagged for conformance the decoder
emits the named start/end annotation pair the Sigrok Decoder section of
specs/accelerometer/fxos8700.md names (active_start / active_ready) - the
conformance checker reads these timestamps directly from the decoded capture.
"""

from .pd import Decoder
