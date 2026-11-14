"""
TMP102 12-bit digital temperature sensor sigrok protocol decoder.

Stacks on the i2c decoder. Recognizes any address in 0x48-0x4B (the four
ADD0 strap positions). Registers are 16-bit big-endian behind a persistent
Register Pointer: the decoder remembers the last pointer written so a later
pointer-less read is attributed correctly.

Annotates, on the data row, TEMPERATURE / T_LOW / T_HIGH reads/writes decoded
to °C (0.0625 °C per LSB, 12-bit, or 13-bit when bit 0 of the register is
set); on the status row, CONFIGURATION decoded to OS/F1:F0/POL/TM/SD/CR1:CR0/
AL/EM. Also annotates the SMBus alert response (address 0x0C) and the
general-call reset (0x00 0x06). Warns on a pointer byte with bits 7:2 set,
writes to the read-only TEMPERATURE register, and unexpected data lengths.

Also emits the conversion timing-marker pair used by
conformance/temperature/tmp102_conformance.py (see
specs/temperature/tmp102.md, "Sigrok Decoder" and "Timing Constraints", and
specs/temperature/tmp102_timing.conf): conversion_start at a CONFIGURATION
write with OS = 1, conversion_done at the first subsequent CONFIGURATION
read returning OS = 1.
"""

from .pd import Decoder
