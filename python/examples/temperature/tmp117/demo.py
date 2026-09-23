"""Demo for the TMP117 — a PT100-replacement cold-chain container thermometer.

Maximum averaging gives the lowest-noise reading. The ALERT pin fires when
the cargo leaves the -25 °C to 8 °C safe transport range, and the callback
reports which boundary tripped. On first start the sensor is calibrated
once against a reference thermometer and the offset is persisted to EEPROM.
After a fixed number of alerts the monitor unsubscribes.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.connection.input_pin import MicroPythonPin
from periph.chips.temperature.tmp117 import (
    TMP117Full, I2C_ADDRESS, SOURCE_HIGH, SOURCE_LOW)

MAX_ALERTS = 10
REFERENCE_C = None   # set to a reference thermometer reading to calibrate once

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
alert_pin = MicroPythonPin(Pin(4, Pin.IN, Pin.PULL_UP))
connection = I2CConnection(i2c, I2C_ADDRESS, int_pin=alert_pin)
sensor = TMP117Full(connection)                          # Create TMP117 Full driver, (connection)

# --- Lowest-noise continuous conversion ---
# 64-conversion averaging with a 1 s cycle gives the quietest result the
# chip can deliver — a cold-chain log needs stability, not speed.
sensor.configure(mode='continuous', averaging=64,
                 cycle_seconds=1.0)                      # Configure conversion, (mode='continuous', averaging=8, cycle_seconds=1.0 s) → None

# --- One-time calibration against a reference thermometer ---
# The observed error is written to TEMP_OFFSET with the EEPROM unlocked, so
# the correction survives power cycles. EEPROM endurance is limited — this
# is a once-per-deployment step, not a loop.
if REFERENCE_C is not None:
    time.sleep(1.1)
    offset = REFERENCE_C - sensor.read_temperature() + sensor.get_temperature_offset()  # Read temperature, () → float °C; Read calibration offset, () → float °C
    sensor.unlock_eeprom()                               # Unlock EEPROM, () → None
    sensor.set_temperature_offset(offset)                # Set calibration offset, (celsius °C) → None
    while sensor.is_eeprom_busy():                       # Check EEPROM busy, () → bool
        time.sleep(0.001)
    sensor.lock_eeprom()                                 # Lock EEPROM, () → None
    print('calibrated, offset {:.4f} °C'.format(offset))

# --- Program the safe transport range ---
# Alert mode flags either side of the window independently; ALERT is
# active-low open-drain, pulled up on the board.
sensor.set_high_limit(8.0)                               # Set THIGH_LIMIT, (celsius °C) → None
sensor.set_low_limit(-25.0)                              # Set TLOW_LIMIT, (celsius °C) → None
sensor.configure_alert(mode='alert', polarity='active_low')  # Configure ALERT, (mode='alert', polarity='active_low', pin_function='alert') → None

alerts = 0


def on_alert(status):
    # --- Report which boundary tripped ---
    # The status mask comes from CONFIGURATION's alert flags; reading them
    # clears them in Alert mode, re-arming the pin for the next excursion.
    global alerts
    alerts += 1
    t = sensor.read_temperature()                        # Read temperature, () → float °C
    if status & SOURCE_HIGH:
        print('{:.2f} °C  too warm — cargo above 8 °C'.format(t))
    elif status & SOURCE_LOW:
        print('{:.2f} °C  too cold — cargo below -25 °C'.format(t))


sensor.on_interrupt(on_alert)                            # Subscribe to ALERT, (callback, int_pin=None) → None
print('monitoring, {:.2f} °C now'.format(sensor.read_temperature()))  # Read temperature, () → float °C

while alerts < MAX_ALERTS:
    time.sleep(1)

# --- Stop monitoring after the demo run ---
sensor.off_interrupt()                                   # Unsubscribe, () → None
