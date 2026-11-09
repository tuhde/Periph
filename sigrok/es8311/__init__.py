"""
ES8311 low-power mono audio codec sigrok protocol decoder (I²C control port).

Stacks on the i2c decoder. Matches addresses 0x18 (CE low) and 0x19 (CE high).
Annotates every register of the register map by name (undocumented registers
as ``reg 0xNN``) and decodes RESET (CSM_ON, master / slave, reset bits),
CLK_MANAGER1-8 (MCLK source, dividers, OSR, speed mode), SDP_IN / SDP_OUT
(format, word length, polarity, mute), 0x0D-0x0F (power / low-power bits),
0x14 (input select, PGA gain in dB), 0x15-0x1C, 0x31-0x37 and 0x44 into named
fields. The ADC / DAC volume registers 0x17 / 0x32 are shown in dB, the
equalizer coefficient bursts (0x1D-0x30, 0x38-0x43) are assembled into 30-bit
values, and a read of 0xFD / 0xFE is annotated as the chip ID.

Warnings: a RESET write that sets CSM_ON while CLK_MANAGER1 was last written
with all clock enables off ("clocks off"); a CLK_MANAGER3 write with an ADC OSR
code of 0-14 (not allowed); undecodable write lengths. No conformance
(timing) annotations are emitted: the user guide quantifies no timing.
"""

from .pd import Decoder
