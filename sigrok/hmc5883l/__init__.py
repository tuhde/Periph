"""
HMC5883L 3-axis magnetometer sigrok protocol decoder.

Stacks on the i2c decoder. Decodes all registers: CONFIG_A (averaging,
ODR, measurement config), CONFIG_B (gain), MODE (operating mode, HS bit),
DATA output registers (X, Z, Y — note non-XYZ order), STATUS (RDY/LOCK),
and ID registers (H43).

Supported address: 0x1E (fixed).
"""

from .pd import Decoder