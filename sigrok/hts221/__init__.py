"""
HTS221 capacitive humidity and temperature sensor sigrok protocol decoder.

Stacks on the i2c decoder and matches I2C address 0x5F. The register
sub-address auto-increment bit (bit 7) is stripped before the register is
looked up. Annotates every register access by name and decodes, from
specs/humidity/hts221.md:

  - WHO_AM_I (0x0F) - checked against 0xBC
  - AV_CONF (0x10) - temperature / humidity averaged sample counts
  - CTRL_REG1 (0x20) - PD, BDU, ODR
  - CTRL_REG2 (0x21) - BOOT, HEATER, ONE_SHOT
  - CTRL_REG3 (0x22) - DRDY polarity, drive and enable
  - STATUS_REG (0x27) - H_DA, T_DA
  - calibration block (0x30-0x3F) - H0/H1/T0/T1 and the ADC references
  - HUMIDITY_OUT / TEMP_OUT burst (0x28-0x2B) - raw values and, once the
    calibration block was seen, converted %rH and degrees C

It warns on writes to reserved or calibration registers. No conformance
annotation pair is emitted (no checked timing constraint).
"""

from .pd import Decoder
