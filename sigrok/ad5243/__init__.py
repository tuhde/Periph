"""
AD5243 dual 256-position I2C digital potentiometer sigrok protocol decoder.

Stacks on the i2c decoder. Recognizes the fixed address 0x2F. A write is an
instruction byte (A0 channel select, SD shutdown flag) followed by one or more
data bytes, each decoded as a wiper code with its code/256 ratio (several data
bytes after one instruction are successive updates of that channel). A read
returns the RDAC of the channel last selected by a write instruction.

Warns on a write to another address, nonzero don't-care bits in the
instruction byte, repeated data bytes after an instruction with SD = 1, a
read before any instruction, and a read of more than one byte. Wiper
settling has no digital completion event, so no timing annotation pair is
emitted (see specs/adc_dac/ad5243.md, "Sigrok Decoder" and
specs/adc_dac/ad5243_timing.conf).
"""

from .pd import Decoder
