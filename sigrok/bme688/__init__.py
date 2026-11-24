"""
BME688 4-in-1 environmental sensor sigrok protocol decoder.

Stacks on the i2c decoder. Decodes:
  - Calibration block 1 burst read (0x8A-0xA0) -> par_T2, par_T3, par_P1-par_P10
  - Calibration block 2 burst read (0xE1-0xEE) -> par_H1-par_H7, par_T1, par_G1-par_G3
  - Single-byte calibration reads (0x00, 0x02) -> res_heat_val, res_heat_range
  - Chip-ID read (0xD0) -> expect 0x61; variant-ID read (0xF0) -> expect 0x01
  - Soft-reset write (0xE0 <- 0xB6)
  - meas_status_0 read (0x1D) -> new_data, gas_measuring, measuring, gas_meas_index
  - ctrl_gas_0 write (0x70) -> heat_off
  - ctrl_gas_1 write (0x71) -> run_gas (bit 5), nb_conv; warns when only the BME680 bit 4 is set
  - gas_wait_x (0x64-0x6D) / gas_wait_shared (0x6E) writes -> ms (forced) or repetitions (parallel)
  - ctrl_hum write (0x72) -> osrs_h
  - ctrl_meas write (0x74) -> osrs_t, osrs_p, mode (sleep/forced/parallel)
  - config write (0x75) -> filter, spi_3w_en
  - Data field burst read (0x1D, 17 bytes per field, up to 3 fields) -> raw ADC values, status, gas range/valid/stable and gas resistance in ohm
  - Heater profile registers (0x50-0x6D)

Supports both I2C addresses: 0x76 (SDO=GND) and 0x77 (SDO=VDDIO).
"""

from .pd import Decoder
