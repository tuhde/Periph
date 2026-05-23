import time
import _testconfig as cfg
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.magnetometer.as5600 import AS5600Full

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
as5600 = AS5600Full(transport)

# --- Magnet detection ---
check_true('magnet_detected', as5600.is_magnet_detected())

# --- Angle readings ---
a = as5600.angle()
check_true('angle in range 0-360', a >= 0.0 and a < 360.0)

r = as5600.angle_raw()
check_true('angle_raw in range 0-4095', r >= 0 and r <= 4095)

ra = as5600.raw_angle()
check_true('raw_angle in range 0-4095', ra >= 0 and ra <= 4095)

rad = as5600.raw_angle_degrees()
check_true('raw_angle_degrees in range 0-360', rad >= 0.0 and rad < 360.0)

# --- Diagnostics ---
check_true('agc non-negative', as5600.agc() >= 0)
check_true('magnitude non-negative', as5600.magnitude() >= 0)

# --- Status ---
sb = as5600.status_byte()
check_true('status_byte valid', sb >= 0 and sb <= 255)

# --- Position configuration (volatile) ---
as5600.set_zero_position(100)
check_eq('zero_position after set', as5600.zero_position(), 100)

as5600.set_max_position(2000)
check_eq('max_position after set', as5600.max_position(), 2000)

as5600.set_max_angle(2048)
check_eq('max_angle after set', as5600.max_angle(), 2048)

# --- Configure ---
as5600.configure(pm=0, hyst=0, outs=0, pwmf=0, sf=0, fth=0, wd=False)
check_true('configure accepted', as5600.is_magnet_detected())

# --- Burn count ---
bc = as5600.burn_count()
check_true('burn_count in range 0-3', bc >= 0 and bc <= 3)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
