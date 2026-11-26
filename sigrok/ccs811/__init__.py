"""
CCS811 indoor air quality gas sensor sigrok protocol decoder.

Stacks on the i2c decoder and matches addresses 0x5A and 0x5B. Annotates every register access
from specs/gas/ccs811.md by name and decodes the fields:

  - STATUS (0x00) — FW_MODE (boot / application), APP_ERASE_DONE, APP_VALID, DATA_READY, ERROR
  - MEAS_MODE (0x01) — drive mode (idle, 1 s, 10 s, 60 s, 250 ms raw), INT_DATARDY, INT_THRESH
  - ALG_RESULT_DATA (0x02) — eCO2 (ppm), TVOC (ppb), STATUS, ERROR_ID and RAW_DATA, for 2-8 byte reads
  - RAW_DATA (0x03) — sense current (uA) and ADC voltage (V)
  - ENV_DATA (0x05) — humidity (%RH) and temperature (degrees C)
  - NTC (0x06) — V_REF / V_NTC in mV and the resistance ratio
  - THRESHOLDS (0x10), BASELINE (0x11), HW_ID / HW_VERSION (0x20 / 0x21), firmware versions
    (0x23 / 0x24), ERROR_ID (0xE0)
  - the boot commands APP_ERASE / APP_DATA / APP_VERIFY / APP_START (0xF1-0xF4) and SW_RESET (0xFF)

Warnings are raised for reads longer than the register size, accesses against the register
direction, MEAS_MODE writes while the chip is in boot mode, SW_RESET / APP_ERASE with a wrong key,
an unexpected HW_ID and set error flags. The CCS811 has no bus-checked timing constraint, so no
conformance annotation pair is emitted (see specs/gas/ccs811_timing.conf).
"""

from .pd import Decoder
