import time
import busio
import _testconfig as cfg
from periph.transport.i2c_circuitpython import I2CTransport
from periph.chips.adc_dac.pcf8591 import PCF8591Full

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
adc = PCF8591Full(transport)

ch0 = adc.read_channel(0)
check_true('read_channel(0) returns int', isinstance(ch0, int))
check_true('read_channel(0) in [0, 255]', 0 <= ch0 <= 255)

ch3 = adc.read_channel(3)
check_true('read_channel(3) in [0, 255]', 0 <= ch3 <= 255)

ch_oob = adc.read_channel(99)
check_true('read_channel(99) clamped to valid range', 0 <= ch_oob <= 255)

all_raw = adc.read_all()
check_true('read_all returns list', isinstance(all_raw, list))
check_true('read_all returns 4 values', len(all_raw) == 4)
for v in all_raw:
    check_true('read_all value in [0, 255]', 0 <= v <= 255)

v0 = adc.read_channel_voltage(0, 3.3, 0.0)
check_true('read_channel_voltage returns float', isinstance(v0, float))
check_true('read_channel_voltage in [0, 3.3]', 0.0 <= v0 <= 3.3)

v_all = adc.read_all_voltage(3.3, 0.0)
check_true('read_all_voltage returns 4 floats', len(v_all) == 4 and all(isinstance(v, float) for v in v_all))

adc.configure(PCF8591Full.MODE_4_SINGLE_ENDED, False, False)
check_true('configure 4 single-ended accepted', True)

adc.configure(PCF8591Full.MODE_3_DIFFERENTIAL, False, False)
diff = adc.read_differential(0)
check_true('read_differential returns int', isinstance(diff, int))
check_true('read_differential in [-128, 127]', -128 <= diff <= 127)

adc.configure(PCF8591Full.MODE_MIXED, False, False)
check_true('configure mixed mode accepted', True)

adc.configure(PCF8591Full.MODE_2_DIFFERENTIAL, False, False)
check_true('configure 2 differential accepted', True)

adc.configure(PCF8591Full.MODE_4_SINGLE_ENDED, True, False)
auto = adc.read_all()
check_true('read_all with auto-increment returns 4 values', len(auto) == 4)

adc.configure(PCF8591Full.MODE_4_SINGLE_ENDED, False, True)
check_true('configure enables DAC', True)

adc.set_dac(0)
check_true('set_dac(0) accepted', True)

adc.set_dac(255)
check_true('set_dac(255) accepted', True)

adc.set_dac(128)
check_true('set_dac(128) accepted', True)

adc.set_dac_voltage(0.0)
check_true('set_dac_voltage(0.0) accepted', True)

adc.set_dac_voltage(1.0)
check_true('set_dac_voltage(1.0) accepted', True)

adc.set_dac_voltage(0.5)
check_true('set_dac_voltage(0.5) accepted', True)

adc.disable_dac()
check_true('disable_dac accepted', True)

i2c.deinit()

print('===DONE: {} passed, {} failed==='.format(passed, failed))
