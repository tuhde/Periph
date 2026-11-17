import os
import sys

from periph.connection.i2c_linux import I2CConnection
from periph.chips.power.lm25066 import LM25066Full
import time

I2C_BUS  = int(os.environ.get('LINUX_I2C_BUS', '1'))
I2C_ADDR = int(os.environ.get('I2C_ADDR', '0x40'), 16)

passed = 0
failed = 0


def check_eq(label, got, expected):
    global passed, failed
    if got == expected:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL {}: got 0x{:04X}, expected 0x{:04X}'.format(label, got, expected))
        failed += 1


def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1


connection = I2CConnection(I2C_BUS, I2C_ADDR)
hotswap = LM25066Full(connection, 1.0, False)

check_true('model is LM25066', hotswap.model() == 'LM25066')
check_true('manufacturer id', hotswap.manufacturer_id() == 'NSC')
check_true('vin readable', 0.0 <= hotswap.voltage_in() <= 24.0)
check_true('vout readable', 0.0 <= hotswap.voltage_out() <= 24.0)
check_true('current readable', hotswap.current() > -1.0)
check_true('power readable', hotswap.power() > -5.0)
check_true('temperature plausible', -40.0 < hotswap.temperature() < 150.0)
check_true('vaux readable', 0.0 <= hotswap.voltage_aux() <= 1.2)
check_true('block diagnostic', 'diagnostic' in hotswap.read_block())
check_true('avg block', 'power' in hotswap.read_average())

hotswap.set_averaging(4)
check_eq('averaging', hotswap.get_averaging(), 4)
hotswap.set_averaging(0)

hotswap.set_vin_uv_warn(8.0)
check_true('vin_uv_warn', abs(hotswap.get_limits()['vin_uv_warn'] - 8.0) < 0.05)
hotswap.set_vin_uv_warn(None)
check_true('vin_uv_warn disabled', hotswap.get_limits()['vin_uv_warn'] is None)
hotswap.set_ot_warn(110.0)
check_true('ot_warn', abs(hotswap.get_limits()['ot_warn'] - 110.0) < 0.1)
hotswap.set_ot_warn(125.0)

hotswap.clear_power_peak()
check_true('peak cleared', hotswap.power_peak() >= 0.0)
hotswap.clear_faults()
check_true('status_word readable', hotswap.status_word() >= 0)
check_true('diagnostic readable', hotswap.diagnostic_word() >= 0)
check_eq('capability', hotswap.capability(), 0xB0)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
