"""
INA3221 three-channel 26 V current/bus-voltage monitor sigrok protocol decoder.

Stacks on the i2c decoder. Decodes all 21 registers across three channels:
Configuration (CH enables, AVG, VBUSCT, VSHCT, MODE), per-channel Shunt
Voltage (µV) and Bus Voltage (mV), per-channel Critical/Warning alert limits,
Shunt-Voltage Sum, Mask/Enable (flags, CVRF), Power-Valid limits, and ID regs.

Supported addresses: 0x40–0x43 (A0 pin: GND/VS/SDA/SCL).
"""

from .pd import Decoder
