"""
MS5611 barometric pressure sensor sigrok protocol decoder.

Stacks on the i2c decoder and matches I2C addresses 0x76 and 0x77. The chip has
no register pointer: every write is a single command byte. Annotates, from
specs/pressure/ms5611.md:

  - commands: reset (0x1E), convert D1 / D2 with the decoded oversampling ratio,
    ADC read (0x00) and PROM read (0xA0 + 2n) with the word index
  - PROM words: 16-bit reads named per word (C1 ... C6, serial code and CRC-4);
    once all eight words were seen the CRC-4 (application note AN520) is checked
  - ADC results: 24-bit raw D1 / D2 depending on the last conversion command, and
    the compensated temperature (degC) and pressure (Pa) once the PROM and both
    raw values are known

It warns on undocumented command bytes, on an ADC read during a conversion
(earlier than the maximum conversion time after the command, when the capture
sample rate is known), on an ADC read that returns 0 and on a PROM CRC
mismatch. No conformance annotation pair is emitted (no checked timing
constraint).
"""

from .pd import Decoder
