"""Minimal example for the DRV8830 — spin a motor forward and reverse.

Constructs the driver with the defaults (no register writes at init) and
alternates between a regulated forward and reverse voltage.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.motor.drv8830 import DRV8830Minimal, I2C_ADDRESS

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
motor = DRV8830Minimal(connection)                       # Create DRV8830 driver, (connection)

for _ in range(5):
    motor.drive(3.0)                                     # Drive at regulated voltage, (voltage V, + = forward) → None
    time.sleep(2)
    motor.drive(-3.0)                                    # Drive at regulated voltage, (voltage V, - = reverse) → None
    time.sleep(2)

motor.stop()                                             # Coast to standby, () → None
