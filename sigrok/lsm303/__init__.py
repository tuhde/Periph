"""LSM303 sigrok protocol decoder.

Sits on top of the sigrok `i2c` decoder and annotates bus transactions with
the LSM303DLHC accelerometer (0x19) and magnetometer (0x1E) register names
and decoded field values.
"""

from .pd import Decoder
