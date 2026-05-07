from machine import I2C
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.adc_dac.mcp4725 import MCP4725Full

i2c = I2C(2, freq=400000)
transport = I2CTransport(i2c, 0x60)
dac = MCP4725Full(transport)

dac.set_voltage(0.5)
dac.set_raw(2048)
dac.set_voltage_eeprom(0.75)
dac.set_raw_eeprom(3000)
result = dac.read()
print(result["code"])
print(result["voltage_fraction"])
print(result["power_down"])
print(result["eeprom_code"])
print(result["eeprom_power_down"])
print(result["eeprom_ready"])
print(result["por"])
dac.set_power_down(1)
dac.wake_up()
dac.reset()
dac.is_eeprom_ready()