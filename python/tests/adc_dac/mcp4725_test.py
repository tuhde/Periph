import time
import _testconfig as cfg
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.adc_dac.mcp4725 import MCP4725Full

from machine import I2C, Pin

passed = 0
failed = 0


def check_eq(label, got, expected):
    global passed, failed
    if got == expected:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL {}: got {}, expected {}'.format(label, got, expected))
        failed += 1


def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1


i2c = I2C(cfg.I2C_ID, sda=Pin(cfg.SDA), scl=Pin(cfg.SCL), freq=cfg.FREQ)
transport = I2CTransport(i2c, cfg.ADDR)
dac = MCP4725Full(transport)

dac.set_voltage(0.5)
dac.set_raw(2048)

result = dac.read()
check_true('code in range', 0 <= result['code'] <= 4095)
check_true('voltage_fraction in range', 0.0 <= result['voltage_fraction'] <= 1.0)
check_true('power_down in range', 0 <= result['power_down'] <= 3)
check_true('eeprom_code in range', 0 <= result['eeprom_code'] <= 4095)
check_true('eeprom_power_down in range', 0 <= result['eeprom_power_down'] <= 3)

dac.set_power_down(1)
result = dac.read()
check_eq('power_down mode 1', result['power_down'], 1)

dac.wake_up()
dac.reset()
check_true('eeprom_ready or write in progress', dac.is_eeprom_ready() in [True, False])

print('===DONE: {} passed, {} failed==='.format(passed, failed))