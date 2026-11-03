"""
MMA8451Q 3-axis MEMS accelerometer sigrok protocol decoder.

Stacks on the i2c decoder. The MMA8451Q (NXP Semiconductors, formerly
Freescale) is a three-axis capacitive MEMS accelerometer with a 14-bit
output (4096 counts/g at +/-2 g) and a selectable +/-2 / +/-4 / +/-8 g
full-scale range.

Supported addresses: 0x1C (SA0 = GND) and 0x1D (SA0 = VDDIO).

Annotated content:

* Every register of the chip's Register Map is named, including both
  interpretations of 0x00 (STATUS when the FIFO is disabled, F_STATUS when
  F_MODE > 0).
* The status/flag registers are decoded into named flags: STATUS /
  F_STATUS, SYSMOD, INT_SOURCE, XYZ_DATA_CFG (HPF_OUT + FS),
  HP_FILTER_CUTOFF (Pulse_HPF_BYP / Pulse_LPF_EN / SEL), PL_STATUS,
  PL_CFG, PL_BF_ZCOMP, P_L_THS_REG, FF_MT_CFG / FF_MT_SRC,
  TRANSIENT_CFG / TRANSIENT_SRC, PULSE_CFG / PULSE_SRC, CTRL_REG1 to
  CTRL_REG5, F_SETUP (F_MODE + F_WMRK) and TRIG_CFG.
* DR (data rate), FS (full scale), MODS / SMODS (oversampling mode) and
  F_MODE (FIFO mode) are decoded into human-readable values.
* The burst read of 0x01..0x06 is assembled into 14-bit signed values and
  converted to g at the last-seen full-scale range (4096 / 2048 / 1024
  counts/g); when F_READ (fast read) is set the 8-bit output is decoded
  instead (64 / 32 / 16 counts/g).
* WHO_AM_I (0x0D) reads are checked against 0x1A and a warning is emitted
  on a mismatch.
* The `timing` row carries the `active_start` / `active_ready` annotation
  pair used by the "first data ready" conformance check (see
  specs/accelerometer/mma8451q_timing.conf).
"""

from .pd import Decoder
