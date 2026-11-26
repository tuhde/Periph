"""
Si1133 UV index and ambient light sensor sigrok protocol decoder.

Stacks on the i2c decoder and matches addresses 0x52 and 0x55. Annotates every register access
from specs/light/si1133.md by name (bit 6 of the register byte disables auto-increment) and
decodes:

  - COMMAND (0x0B), also as the second byte of a HOSTIN0 write — RESET_CMD_CTR, RESET_SW, FORCE,
    PAUSE, START, PARAM_QUERY / PARAM_SET with the parameter name and the decoded value
  - the parameters: CHAN_LIST, ADCCONFIGc (decimation, ADCMUX photodiode name), ADCSENSc (HSIG,
    SW_GAIN, HW_GAIN), ADCPOSTc (24-bit output, POSTSHIFT, THRESH_SEL), MEASCONFIGc (counter),
    MEASRATE, MEASCOUNT0..2, THRESHOLD0..2, BURST, I2C_ADDR
  - RESPONSE0 (0x11) — RUNNING / SUSPEND / SLEEP, command counter or error code; RESPONSE1 (0x10)
    as the answer to the last PARAM_QUERY
  - IRQ_ENABLE (0x0F) and IRQ_STATUS (0x12) channel masks
  - HOSTOUT0..25 (0x13-0x2C) assembled into one value per enabled channel (16-bit unsigned or
    24-bit signed), using the CHAN_LIST / ADCPOST settings seen on the bus
  - PART_ID / HW_ID / REV_ID (0x00-0x02)

Warnings are raised for CMD_ERR responses, a command after which no counter change was seen, a
FORCE with an empty CHAN_LIST and an unexpected PART_ID. The Si1133 has no bus-checked timing
constraint, so no conformance annotation pair is emitted (see specs/light/si1133_timing.conf).
"""

from .pd import Decoder
