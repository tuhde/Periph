"""
LPS22DF absolute pressure / temperature sensor sigrok protocol decoder.

Stacks on the i2c decoder. Decodes:
  - WHO_AM_I read (0x0F) → expect 0xB4
  - CTRL_REG1 write (0x10) → ODR rate (Hz), AVG count
  - CTRL_REG2 write (0x11) → BDU, EN_LPFP, LFPF_CFG, SWRESET, ONESHOT
  - STATUS read (0x27) → P_DA / T_DA / overrun flags
  - Burst pressure read (0x28–0x2A) → raw LSB and computed pressure in Pa
  - Burst temperature read (0x2B–0x2C) → computed °C
  - FIFO_STATUS1 read (0x25) → sample count
  - FIFO burst read (0x78–0x7A) → per-sample Pa

Supports both I²C addresses: 0x5C (SA0=GND) and 0x5D (SA0=VDDIO).
"""

from .pd import Decoder