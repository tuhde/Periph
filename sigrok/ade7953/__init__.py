"""
ADE7953 single-phase multifunction metering IC sigrok protocol decoder.

Stacks on the i2c decoder. Matches the chip's fixed 7-bit address 0x38
and decodes every register access against the register map defined in
specs/power/ade7953.md. The 16-bit register address is written MSB-first
as the leading data byte pair of each transaction, followed by 0..4 data
bytes (depending on the target register's width). For registers with a
documented physical meaning that is gain-independent, the decoded value
is annotated in engineering units (power factor as a −1.0..+1.0 ratio,
line period/frequency in seconds/Hz, phase angle in raw LSBs).
VRMS/IRMSA/IRMSB/power/energy registers are annotated only with their
raw decimal value, since their real-world scale depends on calibration
constants the decoder cannot know.
"""

from .pd import Decoder  # noqa: F401