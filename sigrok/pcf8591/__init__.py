"""
PCF8591 8-bit I2C ADC + DAC sigrok protocol decoder.

Stacks on the i2c decoder. Decodes control-byte writes (with optional DAC
value byte) and ADC read responses (one stale byte followed by N fresh
channel samples). The decoder annotates every byte, the analog input
programming (AIP), the analog output enable (AOE), auto-increment (AI),
and the active channel (CHN).

Supported addresses: 0x48 (A2=A1=A0=GND) through 0x4F (A2=A1=A0=VDD).
"""

from .pd import Decoder
