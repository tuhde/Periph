"""
MCP4725 12-bit I2C DAC with EEPROM sigrok protocol decoder.

Stacks on the i2c decoder. Decodes all three write command types (Fast Write,
Write DAC Register, Write DAC+EEPROM) and the 5-byte read response (DAC
register value, EEPROM value, RDY/BSY, POR, power-down modes). Also decodes
General Call Reset and Wake-Up commands (address 0x00).

Supported addresses: 0x60 (A0=GND), 0x61 (A0=VDD).
"""

from .pd import Decoder
