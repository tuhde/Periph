"""
ADS1013 12-bit I2C ADC (fixed +/-2.048 V) sigrok protocol decoder.

Stacks on the i2c decoder. Recognizes any address in 0x48-0x4B (the four ADDR
strap combinations). Registers are 16-bit big-endian behind a persistent
Address Pointer: the decoder remembers the last pointer written so a later
pointer-less read is attributed correctly.

Annotates, on the data row, CONVERSION reads decoded to the 12-bit code and
volts (1 mV per code); on the status row, CONFIG reads/writes decoded to OS,
MODE and DR (as samples per second). Warns on a reserved Address Pointer, a
write to the read-only CONVERSION register, and any access whose length is
not two bytes.

Also emits the conversion_start / conversion_done timing-marker pair used by
conformance/adc_dac/ads1013_conformance.py (see specs/adc_dac/ads1013.md,
"Sigrok Decoder" and "Timing Constraints", and specs/adc_dac/ads1013_timing.conf):
conversion_start on a Config write with OS = 1, conversion_done on a read of
the CONVERSION register.
"""

from .pd import Decoder
