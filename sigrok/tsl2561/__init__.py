"""
TSL2561 light-to-digital converter sigrok protocol decoder.

Stacks on the i2c decoder. Matches the addresses 0x29, 0x39 and 0x49 (ADDR SEL
= GND / floating / VDD). Every write starts with a command byte, decoded into
CMD, CLEAR, WORD, BLOCK and the register name; the register pointer it sets is
kept for the following (repeated-start or stand-alone) read.

Annotates on the data row: CONTROL power state, TIMING gain / manual /
integration time, the 16-bit interrupt thresholds, INTERRUPT mode and
persistence, the ID part number and revision, and the ADC data - CH0 and CH1
counts and, when both channels are read in one transaction, the lux for the
gain and integration time of the last TIMING write seen (T/FN/CL coefficients;
1x / 402 ms before any TIMING write).

Warnings: a write whose command byte has CMD = 0, a word write to a register
that is not the low half of a 16-bit pair, and command bytes with CLEAR = 1
(an interrupt clear, which is easy to send by accident).

Also emits the conversion_start / conversion_done timing-marker pair used by
conformance/light/tsl2561_conformance.py (see specs/light/tsl2561.md,
"Sigrok Decoder" and "Timing Constraints", and specs/light/tsl2561_timing.conf):
conversion_start on a CONTROL write of 0x03 (power up), conversion_done on the
first following read of a data register (0x0C-0x0F).
"""

from .pd import Decoder
