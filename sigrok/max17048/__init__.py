"""
MAX17048 / MAX17049 lithium fuel gauge sigrok protocol decoder.

Stacks on the i2c decoder. Annotates every register access (pointer write, 16-bit read and
write) with the register name and the decoded value: VCELL (V per cell), SOC (%), CRATE (%/h),
VERSION, MODE, HIBRT, CONFIG (RCOMP, SLEEP, ALSC, ALRT, ATHD), VALRT, VRESET/ID and STATUS
(alert flags). Recognises the power-on reset command (CMD = 0x5400, which the chip does not
ACK) and warns on 8-bit writes, writes to read-only registers and unknown registers. No
timing annotation pair is emitted (the conversions are free-running).

Supported address: 0x36.
"""

from .pd import Decoder
