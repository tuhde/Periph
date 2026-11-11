"""
CAT5171 256-position I2C digital potentiometer sigrok protocol decoder.

Stacks on the i2c decoder. Recognizes addresses 0x2C (AD0 = GND) and 0x2D
(AD0 = VDD). A write is an instruction byte (RS midscale reset, SD shutdown)
followed by one or more data bytes, each decoded as a wiper code with its
code/256 ratio (RAB is unknown to the decoder, so no ohms are shown); several
data bytes after one instruction are successive wiper updates. A read is a
bare one-byte read returning the wiper register.

Warns on a write to another address, nonzero don't-care bits (7, 4:0) in the
instruction byte, a write with no data byte, and a read of more than one byte.

Timing row: poweron_start (capture start) and poweron_ready (first
transaction after power-on), checked by
conformance/adc_dac/cat5171_conformance.py against
specs/adc_dac/cat5171_timing.conf (>= 1 ms).
"""

from .pd import Decoder
