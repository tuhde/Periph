"""
BME680 4-in-1 environmental sensor sigrok protocol decoder.

Stacks on the i2c decoder. Decodes:
  - Calibration block 1 burst read (0x8A-0xA0) -> par_T2, par_T3, par_P1-par_P10
  - Calibration block 2 burst read (0xE1-0xEE) -> par_H1-par_H7, par_T1, par_G1-par_G3
  - Single-byte calibration reads (0x00, 0x02, 0x04) -> res_heat_val, res_heat_range, range_switching_error
  - Chip-ID read (0xD0) -> expect 0x61
  - Soft-reset write (0xE0 <- 0xB6)
  - meas_status_0 read (0x1D) -> new_data, gas_measuring, measuring, gas_meas_index
  - ctrl_gas_0 write (0x70) -> heat_off
  - ctrl_gas_1 write (0x71) -> run_gas, nb_conv
  - ctrl_hum write (0x72) -> osrs_h
  - ctrl_meas write (0x74) -> osrs_t, osrs_p, mode
  - config write (0x75) -> filter, spi_3w_en
  - ADC result burst read (0x1F-0x2B) -> raw press_adc, temp_adc, hum_adc, gas_adc + status
  - Heater profile registers (0x50-0x6D)

Supports both I2C addresses: 0x76 (SDO=GND) and 0x77 (SDO=VDDIO).
"""

from .pd import Decoder
