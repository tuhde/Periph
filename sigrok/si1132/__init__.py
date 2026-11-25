"""
Si1132 UV index and ambient light sensor sigrok protocol decoder.

Stacks on the i2c decoder and matches I2C address 0x60. The register
address bits 7 (reserved) and 6 (auto-increment disable) are stripped
before the register is looked up. Annotates every register access by name
and decodes, from specs/light/si1132.md:

  - PART_ID (0x00) - checked against 0x32; SEQ_ID (0x02) with the
    MEAS_RATE byte-swap erratum for SEQ_ID 0x01
  - HW_KEY (0x07), INT_CFG (0x03), IRQ_ENABLE (0x04), MEAS_RATE, UCOEF
  - COMMAND (0x18) opcodes: NOP, RESET, BUSADDR, ALS_FORCE, ALS_PAUSE,
    ALS_AUTO, GET_CAL and PARAM_SET / PARAM_QUERY with the parameter name;
    PARAM_SET additionally decodes the value written through PARAM_WR
    (CHLIST channels, ADC gain, range, multiplexer)
  - RESPONSE (0x20) - command counter or error code
  - IRQ_STATUS (0x21) - CMD_INT, ALS_INT
  - data block 0x22-0x2D - visible, infrared and AUX counts, with the
    UV index (AUX / 100)

It warns when a measurement is requested before HW_KEY was written or with
an empty CHLIST, on RESPONSE error codes, on an unexpected PART_ID and on
a wrong HW_KEY value. No conformance annotation pair is emitted (no
checked timing constraint).
"""

from .pd import Decoder
