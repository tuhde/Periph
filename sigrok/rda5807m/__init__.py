"""
RDA5807M single-chip FM stereo radio tuner sigrok protocol decoder.

Stacks on the i2c decoder. Unlike most chips, the RDA5807M has no
register-pointer byte: writes always start at the fixed register 0x02 and
reads always start at the fixed register 0x0A, with position in the byte
stream (not an address byte) selecting the register. Decodes write blocks
(CTRL, CHAN with derived tuned frequency, R4, R5, R6, R7) and read blocks
(STATUSA with derived tuned frequency, STATUSB with RSSI/FM_TRUE/FM_READY,
and the four raw RDS blocks shown as hex plus an ASCII-pair hint).

Fixed I2C address: 0x10.
"""

from .pd import Decoder
