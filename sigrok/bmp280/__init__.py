"""
BMP280 barometric pressure / temperature sensor sigrok protocol decoder.

Stacks on the i2c decoder. Decodes:
  - Calibration NVM burst read (0x88–0x9F) → named dig_T1..dig_P9 coefficients
  - Chip-ID read (0xD0) → expect 0x58 (BMP280), 0x60 (BME280), 0x50 (BMP388)
  - Soft-reset write (0xE0 ← 0xB6)
  - status read (0xF3) → measuring, im_update bits
  - ctrl_meas write (0xF4) → osrs_t, osrs_p, mode
  - config write (0xF5) → t_sb, filter, spi3w_en
  - ADC result burst read (0xF7–0xFC) → raw adc_P and adc_T

Supports both I²C addresses: 0x76 (SDO=GND) and 0x77 (SDO=VDDIO).
"""

from .pd import Decoder
