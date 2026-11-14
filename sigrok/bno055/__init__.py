"""
BNO055 9-axis absolute-orientation sensor (Bosch Sensortec) sigrok protocol decoder.

Stacks on the i2c decoder. Matches I2C addresses 0x28 and 0x29. Annotates every
register access by name (page 0 and the page-1 INT_MSK / INT_EN / UNIQUE_ID
registers, tracking PAGE_ID writes); decodes OPR_MODE, PWR_MODE, UNIT_SEL,
SYS_TRIGGER, CALIB_STAT (four 0-3 values), ST_RESULT, INT_STA / INT_EN / INT_MSK
(source names), SYS_STATUS / SYS_ERR (text), TEMP_SOURCE and the axis-remap
registers into named fields; assembles burst reads of the data registers
(acceleration, magnetic field, angular rate, Euler angles, quaternion, linear
acceleration, gravity, temperature) as vectors in physical units, using the
UNIT_SEL value seen earlier in the capture (default m/s^2, dps, degrees); shows
CHIP_ID reads checked against 0xA0, the identification block, the 16-byte unique
ID and the 22-byte calibration offset blocks.

The BNO055 has no bus-decodable timing constraint with a guaranteed bound, so no
named timing annotation pair is emitted. A machine-readable copy of every
transaction is mirrored on OUTPUT_PYTHON as ('REG_READ' | 'REG_WRITE',
(register, bytes)).
"""

from .pd import Decoder
