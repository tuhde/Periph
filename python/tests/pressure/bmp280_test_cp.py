"""BMP280 hardware test — CircuitPython.

Requires _testconfig.py on the device with:
    I2C_ID, SCL, SDA, FREQ, ADDR
"""
import time
import board
import busio
import _testconfig as cfg
from periph.transport.i2c_circuitpython import I2CTransport
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


i2c = busio.I2C(board.SCL, board.SDA, frequency=cfg.FREQ)
transport = I2CTransport(i2c, cfg.ADDR)
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

print('===DONE: {} passed, {} failed==='.format(passed, failed))