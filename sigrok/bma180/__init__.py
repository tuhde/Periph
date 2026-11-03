"""
bma180 sigrok protocol decoder.

Decodes I²C transactions on the bma180 3-axis MEMS accelerometer
(Bosch Sensortec, I²C address 0x38). Annotates register writes/reads
with their names and decoded field values; emits a `wake_start` /
`wake_done` pair across the chip's wake-up timing constraint.
"""

from .pd import Decoder
