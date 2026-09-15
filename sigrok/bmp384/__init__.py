"""
BMP384 high-precision barometric pressure / temperature sensor sigrok protocol decoder.

Stacks on the i2c decoder. Decodes:
  - Calibration NVM burst read (0x31..0x45) -> named par_t1..par_p11 coefficients
  - Chip-ID read (0x00) -> expect 0x50
  - Status read (0x03) -> drdy_temp, drdy_press, cmd_rdy bits
  - PWR_CTRL (0x1B) -> mode, press_en, temp_en
  - OSR (0x1C) -> osr_t, osr_p
  - CONFIG (0x1F) -> iir filter coefficient
  - ODR (0x1D) -> 200 Hz .. 25/16384 Hz
  - CMD (0x7E) -> soft reset (0xB6), FIFO flush (0xB0)
  - ADC result burst read (0x04..0x09) -> raw uncomp_p and uncomp_t
  - FIFO length (0x12..0x13) and FIFO_DATA read (0x14)

Supports both I²C addresses: 0x76 (SDO=GND) and 0x77 (SDO=VDD).
"""

from .pd import Decoder
