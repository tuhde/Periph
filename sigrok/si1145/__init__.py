"""
Si1145 proximity, UV index and ambient light sensor sigrok protocol decoder.

Stacks on the i2c decoder and matches I2C address 0x60 (Si1145/46/47). The
register address bits 7 (reserved) and 6 (auto-increment disable) are
stripped before the register is looked up. Annotates every register access
by name and decodes, from specs/light/si1145.md:

  - PART_ID (0x00) - checked against 0x45/0x46/0x47; SEQ_ID (0x02) with the
    MEAS_RATE byte-swap erratum for SEQ_ID 0x01
  - HW_KEY (0x07), INT_CFG (0x03), IRQ_ENABLE (0x04) sources, MEAS_RATE,
    PS_LED21 / PS_LED3 LED currents, UCOEF
  - COMMAND (0x18) opcodes: NOP, RESET, BUSADDR, PS/ALS/PSALS_FORCE,
    PS/ALS/PSALS_PAUSE, PS/ALS/PSALS_AUTO, GET_CAL and PARAM_SET /
    PARAM_QUERY with the parameter name; PARAM_SET additionally decodes the
    value written through PARAM_WR (CHLIST channels, PS LED selection,
    photodiode multiplexers, ADC gain / recovery, range and PS_ADC_MODE)
  - RESPONSE (0x20) - command counter or error code (invalid setting,
    PS1-3 / visible / IR / AUX ADC overflow)
  - IRQ_STATUS (0x21) - CMD_INT, PS3/PS2/PS1_INT, ALS_INT
  - data block 0x22-0x2D - visible, infrared, PS1-PS3 proximity counts and
    AUX, with the UV index (AUX / 100)

It warns when a measurement is requested before HW_KEY was written or with
an empty CHLIST, on RESPONSE error codes, on an unexpected PART_ID and on a
wrong HW_KEY value. No conformance annotation pair is emitted (no checked
timing constraint; see specs/light/si1145_timing.conf).
"""

from .pd import Decoder
