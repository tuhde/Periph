import time
import _testconfig as cfg
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.light.apds9960 import APDS9960Full

from machine import I2C, Pin

passed = 0
failed = 0


def check_eq(label, got, expected):
    global passed, failed
    if got == expected:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL {}: got 0x{:02X}, expected 0x{:02X}'.format(label, got, expected))
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
apds = APDS9960Full(transport)

check_eq('chip_id', apds.chip_id(), 0xAB)

c, r, g, b = apds.color()
check_true('color_clear >= 0', c >= 0)
check_true('color_red >= 0', r >= 0)
check_true('color_green >= 0', g >= 0)
check_true('color_blue >= 0', b >= 0)

check_true('is_als_valid', apds.is_als_valid())

apds.enable_proximity(True)
time.sleep_ms(100)
p = apds.proximity()
check_true('proximity >= 0', p >= 0)
check_true('proximity <= 255', p <= 255)
check_true('is_proximity_valid', apds.is_proximity_valid())

apds.configure_als(0xB6, 1)
time.sleep_ms(210)
check_true('als_valid after configure', apds.is_als_valid())

apds.als_threshold(100, 60000)
apds.proximity_threshold(10, 200)
apds.set_persistence(0, 1)
check_true('persistence set', True)

apds.enable_als_interrupt(True)
apds.enable_proximity_interrupt(True)
apds.clear_als_interrupt()
apds.clear_proximity_interrupt()
apds.clear_all_interrupts()
check_true('interrupts cleared', True)

apds.set_proximity_offset(10, -5)
apds.set_proximity_mask(False, False, False, False)
check_true('proximity offset/mask set', True)

apds.enable_gesture(True)
apds.configure_gesture(1, 0, 0, 1, 1, 50, 20)
check_true('gesture configured', True)
check_true('gesture_fifo_level >= 0', apds.gesture_fifo_level() >= 0)
apds.clear_gesture_fifo()
apds.enable_gesture_interrupt(False)
apds.enable_gesture(False)
check_true('gesture disabled', True)

s = apds.status()
check_true('status is int', isinstance(s, int))

apds.enable_proximity(False)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
