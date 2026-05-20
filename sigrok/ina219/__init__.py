"""
INA219 26 V, 12-bit current/voltage/power monitor sigrok protocol decoder.

Stacks on the i2c decoder. Decodes register reads and writes with named
fields: Configuration (BRNG, PGA, BADC, SADC, MODE), Shunt Voltage (µV),
Bus Voltage (mV, CNVR, OVF), Power, Current, and Calibration.

Supported addresses: 0x40–0x4F (A1/A0 pins).
"""

from .pd import Decoder
