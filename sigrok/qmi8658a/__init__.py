"""
QMI8658A 6-axis IMU (QST) sigrok protocol decoder.

Stacks on the i2c or the spi decoder. Matches I2C addresses 0x6A and 0x6B and
any SPI chip-select channel; for SPI the read bit (0x80) is stripped from the
first byte and the register is named from the remaining address.

Annotates every register of the register map by name; decodes CTRL1 (SIM,
ADDR_AI, BE, INT enables, FIFO_INT_SEL, SensorDisable), CTRL2 / CTRL3
(self-test, full scale in g / dps, ODR in Hz - accelerometer-only vs 6DOF
read from the current CTRL7), CTRL5 (filter enables and bandwidth), CTRL7,
CTRL8, FIFO_CTRL, FIFO_STATUS, STATUSINT, STATUS0, STATUS1, TAP_STATUS and
COD_STATUS into named flags; names every CTRL9 command write; assembles burst
reads of 0x33-0x40 as temperature (degC), acceleration (m/s^2) and angular
rate (rad/s) at the last-seen full scales; assembles reads of 0x30-0x32 as the
timestamp and 0x5A-0x5C as the step count; shows WHO_AM_I reads checked
against 0x05; flags a burst read at ADDR_AI = 0 and a write to a configuration
register longer than one byte as warnings.

For each Timing Constraint of specs/imu/qmi8658a.md flagged for conformance it
emits the named start/end annotation pair the Sigrok Decoder section names:
reset_recovery_start / reset_recovery_done (RESET write of 0xB0 to the next
transaction) and gyro_startup_start / gyro_startup_done (CTRL7 write setting
gEN to the first read starting in 0x33-0x40). The conformance checker reads
these timestamps directly from the decoded capture. A machine-readable copy of
every transaction is mirrored on OUTPUT_PYTHON as ('REG_READ' | 'REG_WRITE',
(register, bytes)).
"""

from .pd import Decoder
