"""Minimal example for the ADS1115 — print the input voltage.

Constructs the driver with the defaults (±2.048 V, 128 SPS, single-shot; no
I²C traffic until the first read) and reads once per second.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.adc_dac.ads1115 import ADS1115Minimal, I2C_ADDRESS

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
adc = ADS1115Minimal(connection)                              # Create ADS1115 driver, (connection)

while True:
    v = adc.read_voltage(0)                      # Read voltage, (channel=0) → float V
    print('{:.5f} V'.format(v))
    time.sleep(1)
