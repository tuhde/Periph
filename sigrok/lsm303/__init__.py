"""
LSM303 sigrok protocol decoder.

Decodes STMicroelectronics LSM303 accelerometer (0x19) and magnetometer
(0x1E) I²C register transactions.
"""

from .pd import Decoder
