"""
INA260 36 V, 16-bit current/voltage/power monitor (integrated shunt) sigrok protocol decoder.

Stacks on the i2c decoder. Decodes all 8 registers: Configuration (AVG,
VBUSCT, ISHCT, MODE), Current (mA), Bus Voltage (mV), Power (mW),
Mask/Enable (alert functions OCL/UCL/BOL/BUL/POL/CNVR, flags AFF/CVRF/OVF,
polarity, latch), Alert Limit, Manufacturer ID, Die ID. Emits the
conversion_ready_start / conversion_ready_done timing annotations.

Supported addresses: 0x40–0x4F (A1/A0 pins).
"""

from .pd import Decoder
