"""
MPU-6050 6-axis MotionTracking device (accelerometer + gyroscope) sigrok protocol decoder.

Stacks on the i2c decoder. Decodes register writes with decoded field values
(GYRO_CONFIG FS_SEL, ACCEL_CONFIG AFS_SEL, CONFIG DLPF_CFG, PWR_MGMT_1 state),
burst reads of 0x3B–0x48 as computed accel (m/s²), temperature (°C), and gyro
(rad/s) on all three axes, and WHO_AM_I reads annotating the ID value.

Supported addresses: 0x68 (AD0=GND), 0x69 (AD0=VCC).
"""

from .pd import Decoder
