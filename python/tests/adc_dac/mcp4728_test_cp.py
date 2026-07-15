import time
import busio
import _testconfig as cfg
from periph.transport.i2c_circuitpython import I2CTransport
from periph.chips.adc_dac.mcp4728 import MCP4728Full

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
dac = MCP4728Full(transport)

dac.set_voltage(0, 0.5)
check_true('set_voltage(ch0, 0.5) accepted', True)

dac.set_raw(1, 2048)
check_true('set_raw(ch1, 2048) accepted', True)

dac.set_all([0.0, 0.25, 0.5, 1.0])
check_true('set_all accepted', True)

dac.set_voltage_eeprom(0, 0.5, vref=0, gain=1)
check_true('set_voltage_eeprom accepted', True)

dac.set_raw_eeprom(1, 2048, vref=0, gain=1)
check_true('set_raw_eeprom accepted', True)

dac.set_all_eeprom([0.0, 0.25, 0.5, 0.75], [0, 0, 0, 0], [1, 1, 1, 1])
check_true('set_all_eeprom accepted', True)

dac.set_vref(0, 0, 0, 0)
check_true('set_vref accepted', True)

dac.set_gain(1, 1, 1, 1)
check_true('set_gain accepted', True)

dac.set_power_down(MCP4728Full.PD_NORMAL, MCP4728Full.PD_NORMAL,
                   MCP4728Full.PD_NORMAL, MCP4728Full.PD_NORMAL)
check_true('set_power_down normal accepted', True)

dac.set_power_down(MCP4728Full.PD_1K_GND, MCP4728Full.PD_100K_GND,
                   MCP4728Full.PD_500K_GND, MCP4728Full.PD_NORMAL)
check_true('set_power_down modes accepted', True)

state = dac.read()
check_true('read returns list', isinstance(state, list))
check_true('read returns 4 entries', len(state) == 4)
check_true('ch0 code in range', 0 <= state[0]['code'] <= 4095)
check_true('ch0 vref valid', state[0]['vref'] in (0, 1))
check_true('ch0 gain valid', state[0]['gain'] in (1, 2))
check_true('ch0 eeprom_code in range', 0 <= state[0]['eeprom_code'] <= 4095)
check_true('ch3 eeprom_ready in state', 'eeprom_ready' in state[0])

ready = dac.is_eeprom_ready()
check_true('is_eeprom_ready returns bool', isinstance(ready, bool))

dac.software_update()
check_true('software_update accepted', True)

dac.wake_up()
check_true('wake_up accepted', True)

dac.reset()
check_true('reset accepted', True)

i2c.deinit()

print('===DONE: {} passed, {} failed==='.format(passed, failed))
