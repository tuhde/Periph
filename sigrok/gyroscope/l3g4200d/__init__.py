"""
L3G4200D three-axis MEMS gyroscope sigrok protocol decoder.

Stacks on the i2c decoder. Decodes all named registers from
specs/gyroscope/l3g4200d.md, including WHO_AM_I verification against
0xD3, STATUS_REG (ZYXOR/ZOR/YOR/XOR/ZYXDA/ZDA/YDA/XDA), the angular
rate output registers (assembled from two LE bytes each and converted
to dps at the configured full scale), and CTRL_REG1–CTRL_REG5 writes
summarized as human-readable ODR / bandwidth / power-mode / full-scale
strings. INT1_CFG writes are decoded with per-axis enable, AND/OR,
and latch settings. FIFO_CTRL_REG writes are decoded with mode and
watermark level.

For each Timing Constraint above flagged for conformance, emits the
named start/end annotation pair the Sigrok Decoder section of
specs/gyroscope/l3g4200d.md names (odr_start / odr_ready / odr_next) —
the conformance checker reads these timestamps directly from the
decoded capture.
"""

from .pd import Decoder
