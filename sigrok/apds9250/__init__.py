"""
APDS-9250 digital RGB, IR and ambient light sensor sigrok protocol decoder.

Stacks on the i2c decoder. Matches the address 0x52. Annotates every register
access by name (MAIN_CTRL, LS_MEAS_RATE, LS_GAIN, PART_ID, MAIN_STATUS,
LS_DATA_IR/GREEN/BLUE/RED, INT_CFG, INT_PERSISTENCE, LS_THRES_UP/LOW,
LS_THRES_VAR); decodes MAIN_CTRL (SW_RESET, CS_MODE, LS_EN), LS_MEAS_RATE
(resolution and rate), LS_GAIN, PART_ID (part and revision), MAIN_STATUS
(POWER_ON, LS_INT, LS_DATA), INT_CFG (channel, mode, enable), INT_PERSISTENCE
and LS_THRES_VAR; assembles the 3-byte channel counts (LSB first, 20 bit) and
the 20-bit thresholds, names the channel, and shows the count normalised to
counts per gain-second using the gain and resolution seen on the bus. A read
uses the pointer set by the preceding write (with or without a repeated START)
and auto-increments over a block transfer.

Warnings: a write to a reserved or read-only register, a read of the data
registers without a preceding MAIN_STATUS read that showed LS_DATA = 1, a
variation interrupt enabled with the smallest variance (8 counts) at 13 bit
resolution, and a PART_ID whose upper nibble is not 0xB. No timing annotation
pair is emitted, since no timing constraint is conformance-checked.
"""

from .pd import Decoder
