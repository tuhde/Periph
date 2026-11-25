"""
AD5248 dual 256-position I2C digital rheostat sigrok protocol decoder.

Stacks on the i2c decoder. For writes, decodes the instruction byte (channel
from A0, SD shutdown flag) and the following data byte(s) as the wiper code with
its code/256 fraction (RAB is unknown to the decoder, so no ohms are shown);
several data bytes after one instruction are shown as successive updates of that
channel. For reads, shows the returned RDAC value for the channel last selected by
a write instruction. Warns on data bytes following an instruction with SD=1, on
reads longer than one byte and on reserved instruction bits. The wiper settling
time has no digital completion event, so no timing annotation pair is emitted.

Supported addresses: 0x2C-0x2F (AD1/AD0 pins).
"""

from .pd import Decoder
