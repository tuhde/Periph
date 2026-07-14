"""
BME280 combined humidity / pressure / temperature sensor sigrok protocol decoder.

Stacks on the i2c decoder. Decodes:
  - Calibration NVM block 1 (0x88–0x9F) → named dig_T1..dig_P9 coefficients
  - dig_H1 (0xA1) — single byte
  - Calibration NVM block 2 (0xE1–0xE7) → dig_H2..dig_H6, with H4/H5
    sharing byte 0xE5 (lower nibble = H4, upper nibble = H5); both are
    12-bit signed values that the chip driver sign-extends to 16 bits
  - Chip-ID read (0xD0) → expect 0x60 (BME280), 0x58 (BMP280 P/T only)
  - Soft-reset write (0xE0 ← 0xB6)
  - ctrl_hum write (0xF2) → osrs_h
  - status read (0xF3) → measuring, im_update bits
  - ctrl_meas write (0xF4) → osrs_t, osrs_p, mode
  - config write (0xF5) → t_sb (BME280-specific 10 ms / 20 ms at codes 6/7),
    filter, spi3w_en
  - ADC result burst read (0xF7–0xFE) → raw adc_P, adc_T, adc_H

Supports both I²C addresses: 0x76 (SDO=GND) and 0x77 (SDO=VDDIO).
"""

from .pd import Decoder
