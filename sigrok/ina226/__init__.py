"""
INA226 36 V, 16-bit current/voltage/power monitor sigrok protocol decoder.

Stacks on the i2c decoder. Decodes all 10 registers: Configuration (AVG,
VBUSCT, VSHCT, MODE), Shunt Voltage (µV), Bus Voltage (mV), Power, Current,
Calibration, Mask/Enable (alert flags), Alert Limit, Manufacturer ID, Die ID.

Supported addresses: 0x40–0x4F (A1/A0 pins).
"""

from .pd import Decoder
