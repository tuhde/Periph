from machine import I2C
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.adc_dac.mcp4725 import MCP4725Minimal

i2c = I2C(2, freq=400000)
transport = I2CTransport(i2c, 0x60)
dac = MCP4725Minimal(transport)

dac.set_voltage(0.5)