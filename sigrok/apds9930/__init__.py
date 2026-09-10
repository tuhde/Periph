"""
APDS-9930 sigrok protocol decoder.

Sits on top of the `i2c` sigrok decoder and translates raw I²C transactions
into APDS-9930 register names and decoded field values. Matches I²C address
0x39 and emits named start/end annotation pairs for each conformance-checked
timing constraint (see specs/light/apds-9930.md, Sigrok Decoder section).
"""

from .pd import Decoder