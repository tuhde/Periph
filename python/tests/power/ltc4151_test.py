import time
import _testconfig as cfg
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.power.ltc4151 import LTC4151Full

from machine import I2C, Pin

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


i2c = I2C(cfg.I2C_ID, sda=Pin(cfg.SDA), scl=Pin(cfg.SCL), freq=cfg.FREQ)
connection = I2CConnection(i2c, cfg.ADDR)
ltc = LTC4151Full(connection)

check_true('voltage in range',     7.0 <= ltc.voltage() <= 80.0)
check_true('current non-negative', ltc.current()       >= 0.0)
check_true('power non-negative',   ltc.power()         >= 0.0)
check_true('sense voltage range',  0.0 <= ltc.sense_voltage() <= 0.08192)
check_true('adin range',           0.0 <= ltc.adin_voltage() <= 2.048)

d = ltc.read_all()
check_true('read_all vin',         7.0 <= d['vin'] <= 80.0)
check_true('read_raw vin',         0 < ltc.read_raw(LTC4151Full.VIN) < 4096)

check_eq('control default',        ltc.read_control(), 0x0C)
check_true('not snapshot mode',    not ltc.is_snapshot_mode())

check_true('snapshot vin',         7.0 <= ltc.snapshot(LTC4151Full.VIN) <= 80.0)
check_true('back in continuous',   not ltc.is_snapshot_mode())

ltc.set_stuck_bus_timer(False)
check_eq('stuck-bus timer off',    ltc.read_control(), 0x08)
ltc.set_stuck_bus_timer(True)
check_eq('stuck-bus timer on',     ltc.read_control(), 0x0C)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
