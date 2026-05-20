"""
BMP180 barometric pressure / temperature sensor sigrok protocol decoder.

Stacks on the i2c decoder. Decodes:
  - Calibration EEPROM burst read (0xAA–0xBF) → named AC1..MD coefficients
  - Chip-ID read (0xD0) → expect 0x55
  - Soft-reset write (0xE0 ← 0xB6)
  - ctrl_meas write (0xF4) → oss, measurement type, conversion time
  - ADC result reads (0xF6–0xF8) → raw UT or UP with oversampling setting

Fixed address: 0x77.
"""

from .pd import Decoder
