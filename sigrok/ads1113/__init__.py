"""
ADS1113 16-bit delta-sigma ADC sigrok protocol decoder.

Stacks on the i2c decoder. Recognizes any address in 0x48-0x4B (the four
ADDR strap positions). Registers are 16-bit big-endian behind a write-only
Address Pointer: the decoder remembers the last pointer written so a later
pointer-less read is attributed correctly.

Annotates, on the data row, CONVERSION reads (raw code and volts at the
full scale of the last Config write, +-2.048 V until one is seen) and
LO_THRESH / HI_THRESH (code and volts; HI bit 15 set flags conversion-ready
mode), on the status row CONFIG decoded to OS/MUX/PGA/MODE/DR/COMP_*. Warns
on undefined pointers, writes to CONVERSION and unexpected data lengths.

Also emits the conversion_start / conversion_done timing-marker pair used
by conformance/adc_dac/ads1113_conformance.py (Config write with OS=1 to the
first CONVERSION read after it).
"""

from .pd import Decoder
