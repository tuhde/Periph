"""ADXL345 sigrok protocol decoder.

Sits on top of the sigrok `i2c` decoder and annotates bus transactions with
the ADXL345 register names and decoded field values.
"""

from .pd import Decoder