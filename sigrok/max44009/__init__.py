"""
MAX44009 ambient light sensor with ADC sigrok protocol decoder.

Stacks on the i2c decoder. Matches the addresses 0x4A and 0x4B (A0). Annotates
each register access by name (INT_STATUS, INT_ENABLE, CONFIG, LUX_HIGH, LUX_LOW,
UPPER_THRESHOLD, LOWER_THRESHOLD, THRESHOLD_TIMER); decodes CONFIG into CONT,
MANUAL, CDR and the integration time in ms; assembles a read of LUX_HIGH
followed by LUX_LOW into exponent, mantissa and lux (2^E * M * 0.045) and shows
the high byte alone as an approximate lux; decodes the threshold registers as lux
(the upper one with the implied 1111 nibble) and the timer as seconds; shows
INTS and INTE. A read uses the pointer set by the preceding write (with or
without a repeated START).

Warnings: a STOP between the reads of LUX_HIGH and LUX_LOW (the registers may
update between the reads), an exponent of 1111 (overrange), a threshold code
with exponent != 0 and M7 = 0 (invalid format), and writes to the read-only
registers (0x00, 0x03, 0x04). No timing annotation pair is emitted, since no
constraint is conformance-checked.
"""

from .pd import Decoder
