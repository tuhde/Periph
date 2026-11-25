"""
ADS1014 12-bit I2C ADC with PGA and programmable comparator sigrok protocol decoder.

Stacks on the i2c decoder. Recognizes any address in 0x48-0x4B (the four ADDR
strap combinations). Registers are 16-bit big-endian behind a persistent
Address Pointer: the decoder remembers the last pointer written so a later
pointer-less read is attributed correctly.

Annotates, on the data row, CONVERSION reads decoded to the 12-bit code and
volts at the full-scale range of the last-seen Config write (assuming
+/-2.048 V until one is seen) and LO_THRESH / HI_THRESH reads/writes decoded
the same way; on the status row, CONFIG reads/writes decoded to OS, PGA (with
full scale), MODE, DR (as samples per second), COMP_MODE, COMP_POL, COMP_LAT
and COMP_QUE, plus a flag when a HI_THRESH bit 15 = 1 / LO_THRESH bit 15 = 0
pair selects conversion-ready mode. Warns on reserved Address Pointer bits,
a write to the read-only CONVERSION register, and any access whose length is
not two bytes.

Also emits the conversion_start / conversion_done timing-marker pair used by
conformance/adc_dac/ads1014_conformance.py (see specs/adc_dac/ads1014.md,
"Sigrok Decoder" and "Timing Constraints", and specs/adc_dac/ads1014_timing.conf):
conversion_start on a Config write with OS = 1, conversion_done on a read of
the CONVERSION register.
"""

from .pd import Decoder
