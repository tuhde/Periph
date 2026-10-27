"""
LSM303 (LSM303DLHC) sigrok protocol decoder.

Decodes I²C transactions on the LSM303DLHC e-compass's two independent
slaves: the 3-axis accelerometer at 0x19 (8-bit sub-address with
auto-increment bit, repeated-start reads) and the 3-axis magnetometer
at 0x1E. Annotates register writes/reads with their names and decoded
field values (acceleration in g, magnetic field in µT, temperature in
relative °C); emits an `odr_start` / `odr_ready` timing-marker pair
across the accelerometer's `first_data_ready` turn-on constraint.
"""

from .pd import Decoder
