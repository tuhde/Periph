"""Demo for the TSL2561 — a light meter with alarm.

Prints the ID, then takes an auto-ranged lux reading every 2 s for 30 s with
the selected gain, integration time and both channel counts. Then it sets an
interrupt window of ±50 % around the current channel-0 count with persistence
2, reports ``poll_interrupt()`` while you cover the sensor, releases the
interrupt and powers the chip down.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.light.tsl2561 import TSL2561Full, I2C_ADDRESS

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
sensor = TSL2561Full(connection)                         # Create TSL2561 Full driver, (connection)

print('ID 0x{:02X}'.format(sensor.read_id()))            # Read the ID register, () → int

# Light meter: auto-range picks the most sensitive setting that does not saturate.
for _ in range(15):
    lux = sensor.auto_range()                            # Auto-range and read lux, () → float lux
    ch0, ch1 = sensor.read_raw()                         # Read both channels, () → (int, int)
    print('{:9.1f} lx  gain {:2d}x  {:3d} ms  ch0 {:5d}  ch1 {:5d}'.format(
        lux, sensor.get_gain(), sensor.get_integration_time(), ch0, ch1))  # Current settings, () → int
    time.sleep(2)

# Alarm: a window of ±50 % around the current channel-0 count.
ch0, _ = sensor.read_raw()
sensor.set_thresholds(ch0 // 2, min(65535, ch0 * 3 // 2))  # Set channel-0 window, (low, high) → None
sensor.configure_interrupt(1, 2)                         # Level interrupt, 2 cycles outside, (mode, persist) → None
print('window', sensor.get_thresholds(), '- cover the sensor now')  # Read the window, () → (int, int)
for _ in range(10):
    print('outside window:', sensor.poll_interrupt())    # Check ch0 against the window, () → bool
    time.sleep(1)

sensor.clear_interrupt()                                 # Release the level interrupt, () → None
sensor.configure_interrupt(0, 0)
sensor.power_down()                                      # Power down, () → None
print('powered:', sensor.is_powered())                   # Power state, () → bool
