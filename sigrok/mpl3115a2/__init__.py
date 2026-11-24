"""
MPL3115A2 barometric pressure / altitude / temperature sensor sigrok protocol decoder.

Stacks on the i2c decoder and matches address 0x60. Annotates every register access
from specs/pressure/mpl3115a2.md by name and decodes the fields:

  - STATUS / DR_STATUS (0x00 / 0x06) — PTDR, PDR, TDR and overwrite flags
  - OUT_P / OUT_T (0x01-0x05) — pressure (Pa, Q18.2) or altitude (m, Q16.4) and
    temperature (degrees C, Q8.4) bursts; the ALT bit of the last CTRL_REG1 write seen
    selects the interpretation (barometer if none)
  - OUT_P_DELTA / OUT_T_DELTA (0x07-0x0B), F_DATA (0x0E) FIFO samples, P_MIN/T_MIN/P_MAX/T_MAX
  - CTRL_REG1 (0x26) — ALT, OSR (with the minimum sample time), RST, OST, SBYB
  - CTRL_REG2 (0x27) ST step and ALARM_SEL/LOAD_OUTPUT, CTRL_REG3 (0x28) INT polarity and drive,
    CTRL_REG4/5 (0x29/0x2A) and INT_SOURCE (0x12) interrupt bit names
  - F_SETUP (0x0F), F_STATUS (0x0D), SYSMOD (0x11), PT_DATA_CFG (0x13), BAR_IN (0x14),
    targets/windows, offsets (0x2B-0x2D)
  - WHO_AM_I (0x0C) verified against 0xC4

Warnings are raised for writes to read-only registers and for CTRL_REG1 changes (other than
SBYB/OST) while the chip is ACTIVE. The `timing` row carries the named pair used by the
conformance checker: `conversion_start` on a CTRL_REG1 write that sets OST and
`conversion_done` on the first following STATUS/DR_STATUS read with PTDR set.
"""

from .pd import Decoder
