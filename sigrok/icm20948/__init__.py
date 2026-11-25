"""
ICM-20948 9-axis MotionTracking device (TDK InvenSense) sigrok protocol decoder.

Stacks on the i2c decoder. Matches the primary device at 0x68 / 0x69 and the
AK09916 magnetometer at 0x0C (only visible on the host bus when the
pass-through bypass is active).

The registers of the ICM-20948 are organised in four user banks selected through
REG_BANK_SEL (0x7F); the decoder tracks every REG_BANK_SEL write and annotates
each register of the current bank by name, showing the bank number in every
annotation. PWR_MGMT_1 / PWR_MGMT_2, USER_CTRL, INT_PIN_CFG, INT_ENABLE*,
INT_STATUS*, GYRO_CONFIG_1 / ACCEL_CONFIG (full scale and low-pass filter), the
sample-rate dividers (as Hz), I2C_MST_CTRL (clock in kHz) and the I2C_SLVx_*
registers are decoded into named flags. Burst reads of 0x2D–0x3A (bank 0) are
assembled into acceleration (m/s²), angular rate (rad/s) and temperature (°C)
at the last-seen full scale; reads of EXT_SLV_SENS_DATA_00..08 are assembled
into the magnetometer block in µT. WHO_AM_I reads are checked against 0xEA.

For the Power-on Timing Constraint flagged for conformance, emits the named
start/end annotation pair the Sigrok Decoder section of specs/imu/icm20948.md
names (poweron_start / poweron_ready) in the timing row: the PWR_MGMT_1 write
with DEVICE_RESET set (0x80), then the first subsequent register read.
"""

from .pd import Decoder
