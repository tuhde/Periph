"""
MCP4728 sigrok protocol decoder.

Sits on top of the I²C sigrok decoder and annotates bus transactions with
the MCP4728 command type, channel index, decoded DAC code, V_REF, gain,
and power-down mode.

The MCP4728 has no register address byte. Command type is encoded in
the upper bits of the first data byte following the I²C address.
"""

from .pd import Decoder
