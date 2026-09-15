"""
LPS33HW water-resistant MEMS pressure sensor sigrok protocol decoder.

Stacks on the i2c decoder. Decodes every register in the LPS33HW Register
Map from specs/pressure/lps33hw.md:

  - INTERRUPT_CFG (0x0B) — AUTORIFP, RESET_ARP, AUTOZERO, RESET_AZ,
    DIFF_EN, LIR, PLE, PHE
  - THS_P_L/H (0x0C/0x0D) — 16-bit pressure threshold in hPa (1 LSB = 1/16 hPa)
  - WHO_AM_I (0x0F) — expect 0xB1
  - CTRL_REG1 (0x10) — ODR (Power-down/1/10/25/50/75 Hz), BDU, EN_LPFP /
    LPFP_CFG bandwidth, SIM (SPI mode)
  - CTRL_REG2 (0x11) — BOOT, SWRESET, ONE_SHOT, FIFO_EN, STOP_ON_FTH,
    IF_ADD_INC, I2C_DIS
  - CTRL_REG3 (0x12) — INT_H_L polarity, PP_OD drive, F_FSS5/F_FTH/F_OVR/DRDY
    routing, INT_S signal selection
  - FIFO_CTRL (0x14) — F_MODE name (Bypass / FIFO / Stream / ...), watermark
  - REF_P_XL/L/H (0x15–0x17) — 24-bit reference pressure in hPa
  - RPDS_L/H (0x18/0x19) — 16-bit signed pressure offset in hPa
  - RES_CONF (0x1A) — LC_EN low-current mode
  - INT_SOURCE (0x25) — BOOT_STATUS, IA, PL, PH
  - FIFO_STATUS (0x26) — FTH_FIFO, OVR, FSS[5:0] count
  - STATUS (0x27) — T_OR, P_OR, T_DA, P_DA (pressure/temperature data-available)
  - PRESS_OUT_XL/L/H + TEMP_OUT_L/H (0x28–0x2C) — 24-bit pressure + 16-bit
    temperature burst, with converted pressure in Pa/hPa and temperature in °C
  - LPFP_RES (0x33) — LPF reset (read to flush transitory state)

The decoder emits a named annotation pair for the one-shot conversion timing
constraint (one_shot_start / one_shot_done) — see specs/pressure/lps33hw.md
"Timing Constraints" and specs/pressure/lps33hw_timing.conf, and the
conformance/pressure/lps33hw_conformance.py checker.

Supports both I²C addresses: 0x5C (SA0=GND, default) and 0x5D (SA0=VDD).
"""

from .pd import Decoder