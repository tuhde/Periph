"""
MPL115A2 barometric pressure sensor sigrok protocol decoder.

Stacks on the i2c decoder. Decodes:
  - Coefficient block read (0x04-0x0B) -> named a0, b1, b2, c12 values
  - ADC register read (0x00-0x03) -> Padc / Tadc counts and, once the
    coefficients were seen in the capture, the compensated pressure
  - CONVERT write (0x12) -> conversion_start; the first following ADC read
    -> conversion_done (timing row)
  - warnings for writes to read-only registers

Fixed address: 0x60.
"""

from .pd import Decoder
