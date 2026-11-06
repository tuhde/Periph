"""Minimal example for the ADS1014 — print the input voltage.

Constructs the driver with the defaults (±2.048 V range, 1600 SPS,
single-shot, comparator disabled) and reads AIN0 − AIN1 once per second.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.adc_dac.ads1014 import ADS1014Minimal, I2C_ADDRESS

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
adc = ADS1014Minimal(connection)                         # Create ADS1014 driver, (connection)

while True:
    raw = adc.read_raw()                                 # Read raw code, () → int (−2048…2047)
    v = adc.read_voltage()                               # Read voltage, () → float V
    print('{} -> {:.3f} V'.format(raw, v))
    time.sleep(1)
