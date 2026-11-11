import os
import sys

from periph.connection.i2c_linux import I2CConnection
from periph.chips.adc_dac.ads7828 import ADS7828Full

I2C_BUS  = int(os.environ.get('LINUX_I2C_BUS', '1'))
I2C_ADDR = int(os.environ.get('I2C_ADDR', '0x48'), 16)

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

adc = ADS7828Full(connection)

raw = adc.read_raw(0)
check_true('read_raw(0) is int', isinstance(raw, int))
check_true('read_raw(0) in [0, 4095]', 0 <= raw <= 4095)
check_true('read_raw(99) clamped', 0 <= adc.read_raw(99) <= 4095)
v = adc.read_voltage(1)
check_true('read_voltage in [0, 2.5]', 0.0 <= v <= 2.5)
allv = adc.read_all()
check_true('read_all returns 8 floats', len(allv) == 8 and all(isinstance(x, float) for x in allv))
check_true('read_differential_raw in range', 0 <= adc.read_differential_raw(0) <= 4095)
check_true('read_differential inverted >= 0', adc.read_differential(1, True) >= 0.0)
sv = adc.read_signed_differential(2)
check_true('read_signed_differential in [-2.5, 2.5]', -2.5 <= sv <= 2.5)
check_true('lsb is vref/4096', abs(adc.lsb() - 2.5 / 4096) < 1e-9)
check_true('internal reference selected', adc.uses_internal_reference() and adc.get_reference() == 2.5)
scan = adc.read_scan([0, 3, 7])
check_true('read_scan returns 3 codes', len(scan) == 3 and all(0 <= r <= 4095 for r in scan))
adc.set_power_mode(ADS7828Full.REF_ON_ADC_ON)
check_true('set_power_mode accepted', True)
adc.set_reference(5.0)
check_true('external reference selected', not adc.uses_internal_reference() and adc.get_reference() == 5.0)
adc.set_reference(None)
check_true('internal reference restored', adc.uses_internal_reference())
adc.power_down()
check_true('power_down accepted', True)
check_true('read after power_down works', 0 <= adc.read_raw(0) <= 4095)

connection.close()

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
