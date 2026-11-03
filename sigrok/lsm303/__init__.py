"""
LSM303 3-axis accelerometer + magnetometer e-compass sigrok protocol decoder.

Stacks on the i2c decoder. Decodes both dies of the LSM303:

* The accelerometer slave at 0x19 — every register from the Accelerometer
  register map is annotated by name (the auto-increment bit in the sub-address
  is stripped and shown separately). CTRL_REG1_A–CTRL_REG6_A, STATUS_REG_A,
  FIFO_CTRL_REG_A, FIFO_SRC_REG_A, INTx_CFG_A, INTx_SOURCE_A, CLICK_CFG_A,
  CLICK_SRC_A and CLICK_THS_A are decoded into named flags; ODR, FS, the
  LPen/HR operating mode, HPM and the FIFO mode are decoded into
  human-readable values; the 6-byte burst of OUT_X_L_A..OUT_Z_H_A is assembled
  into signed values converted to *g* at the last-seen range and mode.
* The magnetometer slave at 0x1E — every magnetometer register is annotated
  by name, CRA_REG_M, CRB_REG_M (as the full scale in µT), MR_REG_M (as the
  mode) and SR_REG_M are decoded; the 6-byte read of OUT_X_H_M..OUT_Z_L_M
  (X, Z, Y) is assembled into µT at the last-seen gain, TEMP_OUT into
  relative °C, and reads of IRA/IRB/IRC are checked against
  0x48 0x34 0x33.

For the First-data-ready Timing Constraint above flagged for conformance,
emits the named start/end annotation pair the Sigrok Decoder section of
specs/accelerometer/lsm303.md names (odr_start / odr_ready) — the conformance
checker reads these timestamps directly from the decoded capture.
"""

from .pd import Decoder
