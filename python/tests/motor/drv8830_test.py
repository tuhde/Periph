"""MicroPython hardware test for the DRV8830 — runs against real hardware.

Drives the motor forward, reverse, brake and coast, reading back the CONTROL
register after each command, and checks the fault register can be cleared.
"""

import time
import machine
import _testconfig as cfg
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.motor.drv8830 import DRV8830Minimal, DRV8830Full
from machine import Pin

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


i2c = machine.I2C(cfg.I2C_ID, sda=Pin(cfg.SDA), scl=Pin(cfg.SCL), freq=cfg.FREQ)
connection = I2CConnection(i2c, cfg.ADDR)

motor = DRV8830Minimal(connection)
check_true('construct_minimal', isinstance(motor, DRV8830Minimal))

full = DRV8830Full(connection)
full.drive(2.0)
time.sleep(0.1)
voltage, direction = full.read_output()
check_true('drive_forward_direction', direction == 'forward')
check_true('drive_forward_voltage', abs(voltage - 2.0) < 0.1)

full.drive(-1.0)
voltage, direction = full.read_output()
check_true('drive_reverse_direction', direction == 'reverse')

full.brake()
check_true('brake_direction', full.read_output()[1] == 'brake')

full.stop()
check_true('stop_direction', full.read_output()[1] == 'coast')

full.clear_fault()
check_true('clear_fault', not full.read_fault()[0])

print('===DONE: %d passed, %d failed===' % (passed, failed))
