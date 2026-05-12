import time
import busio
import _testconfig as cfg
from periph.transport.i2c_circuitpython import I2CTransport
from periph.chips.adc_dac.mcp4725 import MCP4725Full

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


i2c = busio.I2C(cfg.SCL, cfg.SDA, frequency=cfg.FREQ)
transport = I2CTransport(i2c, cfg.ADDR)
dac = MCP4725Full(transport)

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

dac.set_power_down(MCP4725Full.PD_NORMAL)
check_true('set_power_down(NORMAL) accepted', True)

dac.set_power_down(MCP4725Full.PD_1K_GND)
check_true('set_power_down(1K) accepted', True)

dac.set_power_down(MCP4725Full.PD_100K_GND)
check_true('set_power_down(100K) accepted', True)

dac.set_power_down(MCP4725Full.PD_500K_GND)
check_true('set_power_down(500K) accepted', True)

dac.wake_up()
check_true('wake_up accepted', True)

dac.reset()
check_true('reset accepted', True)

ready = dac.is_eeprom_ready()
check_true('is_eeprom_ready returns bool', isinstance(ready, bool))

i2c.deinit()

print('===DONE: {} passed, {} failed==='.format(passed, failed))