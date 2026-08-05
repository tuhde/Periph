import os
import sys

from periph.connection.i2c_linux import I2CConnection
from periph.chips.adc_dac.mcp4725 import MCP4725Full
import time

I2C_BUS  = int(os.environ.get('LINUX_I2C_BUS', '1'))
I2C_ADDR = int(os.environ.get('I2C_ADDR', '0x60'), 16)

passed = 0
failed = 0


def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1


connection = I2CConnection(I2C_BUS, I2C_ADDR)
dac = MCP4725Full(connection)

dac.set_voltage(0.5)
check_true('set_voltage(0.5) accepted', True)

dac.set_raw(2048)
check_true('set_raw(2048) accepted', True)

dac.set_voltage_eeprom(0.5)
check_true('set_voltage_eeprom(0.5) accepted', True)

dac.set_raw_eeprom(2048)
check_true('set_raw_eeprom(2048) accepted', True)

state = dac.read()
check_true('read returns dict', isinstance(state, dict))
check_true('code in read result', 'code' in state)
check_true('voltage_fraction in read result', 'voltage_fraction' in state)
check_true('power_down in read result', 'power_down' in state)
check_true('eeprom_code in read result', 'eeprom_code' in state)
check_true('eeprom_power_down in read result', 'eeprom_power_down' in state)
check_true('eeprom_ready in read result', 'eeprom_ready' in state)

dac.wake_up()
check_true('wake_up accepted', True)

dac.reset()
check_true('reset accepted', True)

ready = dac.is_eeprom_ready()
check_true('is_eeprom_ready returns bool', isinstance(ready, bool))

connection.close()

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)