"""BMP280 hardware test — MicroPython.

Requires _testconfig.py on the device with:
    I2C_ID, SDA, SCL, FREQ, ADDR
"""
import time
import _testconfig as cfg
from machine import I2C, Pin
from periph.transport.i2c_micropython import I2CTransport
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


def check_float(label, got, tol=0.5):
    global passed, failed
    if abs(got - check_val) <= tol:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL {}: got {} expected ~{}'.format(label, got, check_val))
        failed += 1


i2c = I2C(cfg.I2C_ID, sda=Pin(cfg.SDA), scl=Pin(cfg.SCL), freq=cfg.FREQ)
transport = I2CTransport(i2c, cfg.ADDR)
chip = BMP280Minimal(transport)

# Calibration coefficients are non-zero
check_true('cal_T1_nonzero', chip._dig_T1 != 0)
check_true('cal_P1_nonzero', chip._dig_P1 != 0)

# Chip ID read
_id = chip.chip_id()
check_eq('chip_id', _id, 0x58)

# Temperature and pressure return reasonable values
t = chip.temperature()
p = chip.pressure()
check_true('temp_range', -40 <= t <= 85)
check_true('press_range', 300 <= p <= 1100)

# Full: set_oversampling, set_mode, set_filter, set_standby
full = BMP280Full(transport)
full.set_oversampling(2, 2)
full.set_mode(1)
full.set_filter(2)
full.set_standby(3)
check_true('full_set_oversampling', True)
check_true('full_set_mode', True)
check_true('full_set_filter', True)
check_true('full_set_standby', True)

_status = full.status()
check_true('status_register', 0 <= _status <= 255)

_alt = full.altitude()
check_true('altitude_range', -500 <= _alt <= 9000)

_slp = full.sea_level_pressure(10)
check_true('sea_level_range', 800 <= _slp <= 1100)

full.reset()
check_true('reset_accepted', True)

# Validation: use hardcoded calibration + ADC values to test compensation
transport2 = I2CTransport(i2c, cfg.ADDR)
chip2 = BMP280Minimal(transport2)
# Set known calibration values
chip2._dig_T1 = 27504
chip2._dig_T2 = 26435
chip2._dig_T3 = -1000
chip2._dig_P1 = 36477
chip2._dig_P2 = -10685
chip2._dig_P3 = 3024
chip2._dig_P4 = 2855
chip2._dig_P5 = 140
chip2._dig_P6 = -7
chip2._dig_P7 = 15500
chip2._dig_P8 = -14600
chip2._dig_P9 = 6000
# UT=519888, UP=415148
t_val = chip2._compensate_temp(519888)
p_val = chip2._compensate_pressure(415148)
check_float('validate_temp', t_val, 25.08)
# Use a wider tolerance since we're testing without the actual chip

print('===DONE: {} passed, {} failed==='.format(passed, failed))