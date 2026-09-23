"""Minimal example for the VL53L0X — print the distance.

Constructs the driver with the defaults (full init, ~33 ms timing budget)
and takes one single-shot measurement every 100 ms.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.tof.vl53l0x import VL53L0XMinimal, I2C_ADDRESS

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
sensor = VL53L0XMinimal(connection)                      # Create VL53L0X driver, (connection)

while True:
    d = sensor.distance()                                # Measure distance, () → int mm
    if sensor.range_valid():                             # Check last measurement, () → bool
        print('{} mm'.format(d))
    else:
        print('out of range')
    time.sleep(0.1)
