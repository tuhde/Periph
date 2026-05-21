"""BMP280 hardware test — Linux kernel.

Run on host with:
    I2C_BUS=1 I2C_ADDR=0x76 python3 bmp280_test_linux.py
"""
import os
import sys

try:
    from smbus2 import SMBus
except ImportError:
    SMBus = None

from periph.transport.i2c_linux import I2CTransport
from periph.chips.pressure.bmp280 import BMP280Minimal, BMP280Full

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


def check_eq(label, got, expected):
    global passed, failed
    if got == expected:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL {}: got {} expected {}'.format(label, got, expected))
        failed += 1


bus_num = int(os.environ.get('I2C_BUS', 1))
addr = int(os.environ.get('I2C_ADDR', '0x76'), 0)

transport = I2CTransport(bus_num, addr)
chip = BMP280Minimal(transport)

check_true('cal_T1_nonzero', chip._dig_T1 != 0)
check_true('cal_P1_nonzero', chip._dig_P1 != 0)

_id = chip.chip_id()
check_eq('chip_id', _id, 0x58)

t = chip.temperature()
p = chip.pressure()
check_true('temp_range', -40 <= t <= 85)
check_true('press_range', 300 <= p <= 1100)

full = BMP280Full(transport)
_status = full.status()
check_true('status_register', 0 <= _status <= 255)

_alt = full.altitude()
check_true('altitude_range', -500 <= _alt <= 9000)

_slp = full.sea_level_pressure(10)
check_true('sea_level_range', 800 <= _slp <= 1100)

full.reset()
check_true('reset_accepted', True)

print('===DONE: {} passed, {} failed==='.format(passed, failed))