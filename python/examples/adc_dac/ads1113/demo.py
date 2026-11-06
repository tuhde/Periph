"""Demo for the ADS1113 — a real-world scenario.

Slow battery-voltage logger: sample once per second at 8 SPS (lowest noise)
in single-shot mode, then switch to continuous conversion and average ten
readings.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.adc_dac.ads1113 import ADS1113Full, I2C_ADDRESS

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
adc = ADS1113Full(connection)                                # Create ADS1113 Full driver, (connection)

# --- Configure for low-noise slow sampling ---
# 8 SPS gives the longest integration time per conversion, which suppresses
# mains and switching noise on a slowly varying battery voltage.
adc.set_data_rate(adc.DR_8)                              # Set data rate, (dr 0–7) → None

# --- Log single-shot readings ---
# Each read_voltage() wakes the chip, converts and powers down again,
# keeping the average supply current in the microamp range.
for _ in range(5):
    print('{:.5f} V'.format(adc.read_voltage()))         # Read voltage, () → float V
    time.sleep(1)

# --- Average continuous readings ---
# Continuous mode converts back-to-back; averaging ten results (1.25 s)
# reduces random noise by about a factor of three.
adc.start_continuous()                                   # Start continuous, () → None
time.sleep(0.2)
acc = 0.0
for _ in range(10):
    acc += adc.read_continuous()                         # Read continuous, () → float V
    time.sleep(0.125)
adc.stop_continuous()                                    # Stop continuous, () → None
print('average {:.5f} V'.format(acc / 10))
