"""
SPL06-007 barometric pressure and temperature sensor sigrok protocol decoder.

Stacks on the i2c decoder and matches addresses 0x76 and 0x77. Annotates every register access
from specs/pressure/spl06.md by name and decodes the fields:

  - PRS_B2..0 / TMP_B2..0 (0x00-0x05) — 24-bit two's-complement raw results, and (once the
    coefficients and configuration were seen) the compensated pressure in Pa and temperature in
    degrees C; with FIFO_EN set, PRS_B2..0 reads are FIFO entries (LSB 1 = pressure, 0 = temperature)
  - PRS_CFG (0x06) / TMP_CFG (0x07) — measurement rate and oversampling, TMP_EXT
  - MEAS_CFG (0x08) — COEF_RDY, SENSOR_RDY, TMP_RDY, PRS_RDY and the measurement mode
  - CFG_REG (0x09) — INT_HL, interrupt enables, T_SHIFT / P_SHIFT, FIFO_EN, SPI_MODE
  - INT_STS (0x0A), FIFO_STS (0x0B), RESET (0x0C: soft reset / FIFO flush)
  - ID (0x0D) verified against 0x10
  - COEF (0x10-0x21) — the 18-byte block decoded into c0, c1, c00, c10, c01, c11, c20, c21, c30

Warnings are raised for TMP_CFG writes with TMP_EXT = 0, oversampling above 8x without the matching
shift bit, result reads before the matching ready flag, writes to read-only registers and an
unexpected ID. The SPL06 has no bus-checked timing constraint, so no conformance annotation pair
is emitted (see specs/pressure/spl06_timing.conf).
"""

from .pd import Decoder
