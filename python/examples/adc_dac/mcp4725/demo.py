from machine import I2C
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.adc_dac.mcp4725 import MCP4725Full
import time

i2c = I2C(2, freq=400000)
transport = I2CTransport(i2c, 0x60)
dac = MCP4725Full(transport)

step = 1.0 / 20.0
while True:
    for fraction in [i * step for i in range(21)]:
        dac.set_voltage(fraction)
        voltage = round(fraction * 3.3, 3)
        print(f"{fraction:.2f} -> {voltage:.3f} V")
        time.sleep_ms(100)
    for fraction in [i * step for i in range(20, -1, -1)]:
        dac.set_voltage(fraction)
        voltage = round(fraction * 3.3, 3)
        print(f"{fraction:.2f} -> {voltage:.3f} V")
        time.sleep_ms(100)