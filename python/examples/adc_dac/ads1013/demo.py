"""Demo for the ADS1013 — a battery-voltage logger.

The cell voltage is read through a 2:1 resistor divider (AIN0, AIN1 to GND)
once per second in single-shot mode at 128 SPS, printing raw code, divider
voltage and battery voltage plus a running minimum and maximum. The driver
then switches to continuous mode at 3300 SPS and prints the average of 100
samples.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.adc_dac.ads1013 import ADS1013Full, I2C_ADDRESS, DR_128, DR_3300

DIVIDER_RATIO = 2.0
LOG_SAMPLES = 10

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
adc = ADS1013Full(connection)                            # Create ADS1013 Full driver, (connection)

# --- Slow, low-noise single-shot logging ---
# 128 SPS gives the lowest noise; one conversion per second keeps the chip
# in power-down in between.
adc.set_data_rate(DR_128)                                # Set data rate, (dr 0–6) → None
low = None
high = None
for _ in range(LOG_SAMPLES):
    raw = adc.read_raw()                                 # Read raw code, () → int (−2048…2047)
    v = adc.read_voltage()                               # Read voltage, () → float V
    battery = v * DIVIDER_RATIO
    low = battery if low is None else min(low, battery)
    high = battery if high is None else max(high, battery)
    print('raw {} divider {:.3f} V battery {:.3f} V (min {:.3f}, max {:.3f})'.format(
        raw, v, battery, low, high))
    time.sleep(1)

# --- Fast continuous averaging ---
# At 3300 SPS in continuous mode, 100 samples take about 30 ms.
adc.set_data_rate(DR_3300)                               # Set data rate, (dr 0–6) → None
adc.start_continuous()                                   # Start continuous conversion, () → None
time.sleep(0.01)
total = 0.0
for _ in range(100):
    total += adc.read_continuous()                       # Read latest voltage, () → float V
    time.sleep(1.0 / adc.data_rate_sps())                # Read data rate, () → int SPS
adc.stop_continuous()                                    # Stop continuous conversion, () → None
print('average battery {:.3f} V'.format(total / 100 * DIVIDER_RATIO))
