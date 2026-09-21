"""
MPU-9250 sigrok protocol decoder.

Decodes I2C transactions to/from the MPU-9250 9-axis MotionTracking device
(accelerometer + gyroscope) and the AK8963 magnetometer at address 0x0C.
Annotates register reads/writes with decoded field values and computed physical units.
"""

from .pd import Decoder