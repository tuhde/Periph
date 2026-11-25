"""
CS43L22 stereo DAC with headphone and Class-D speaker amplifiers sigrok protocol
decoder (I²C control port).

Stacks on the i2c decoder. Matches addresses 0x4A (AD0 = DGND) and 0x4B (AD0 = VL).
Each write is decoded as the memory address pointer byte (register name, INCR flag)
followed by data bytes mapped to consecutive registers when INCR is set, and each
read uses the last written pointer. Every documented register is annotated with its
fields: power state (0x01 / 0x9E / 0x9F), clocking (AUTO, speed, ratio), interface
format and word length, headphone gain G, mute flags, volumes in dB, tone gains,
beep frequency / times / volume / mode, limiter thresholds, status flags and VP level;
the chip ID read-back is checked against 0xE0 in bits 7:3. The required initialization
block (0x00 <- 0x99, 0x47 <- 0x80, 0x32 bit 7 set and cleared, 0x00 <- 0x00) is shown
as one labelled group.

Warnings: a write to a reserved register (other than the required-initialization ones),
POWER_CTL_1 values other than 0x01 / 0x9E / 0x9F, a POWER_CTL_1 = 0x9E write that comes
before the required initialization in the capture, and beep timing registers written
while a beep mode is active. No timing annotation pair is emitted: none of the stated
timing constraints involves a bus-decodable start / end pair.
"""

from .pd import Decoder
