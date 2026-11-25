"""Minimal example for the ADS1013 — print the input voltage.

Constructs the driver with the defaults (fixed ±2.048 V range, 1600 SPS,
single-shot) and reads AIN0 − AIN1 once per second.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.adc_dac.ads1013 import ADS1013Minimal, I2C_ADDRESS

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
adc = ADS1013Minimal(connection)                         # Create ADS1013 driver, (connection)

while True:
    raw = adc.read_raw()                                 # Read raw code, () → int (−2048…2047)
    v = adc.read_voltage()                               # Read voltage, () → float V
    print('{} -> {:.3f} V'.format(raw, v))
    time.sleep(1)
