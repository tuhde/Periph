"""Demo for the MCP9808 — an industrial freezer temperature monitor.

The healthy freezer range is -25 °C to -15 °C; -5 °C means the door has
been left open too long. The Alert output fires in interrupt mode each time
the temperature leaves or re-enters the window, and the callback reports
which boundary tripped. After a fixed number of alerts the monitor shuts
the Alert output down and unsubscribes.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.connection.input_pin import MicroPythonPin
from periph.chips.temperature.mcp9808 import (
    MCP9808Full, I2C_ADDRESS, SOURCE_LOWER, SOURCE_UPPER, SOURCE_CRITICAL)

MAX_ALERTS = 10

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
alert_pin = MicroPythonPin(Pin(4, Pin.IN, Pin.PULL_UP))
connection = I2CConnection(i2c, I2C_ADDRESS, int_pin=alert_pin)
sensor = MCP9808Full(connection)                         # Create MCP9808 Full driver, (connection)

# --- Trade resolution for faster sampling ---
# 0.25 °C is plenty for a freezer and converts in ~65 ms instead of 250 ms,
# so a door opening shows up in the next reading almost immediately.
sensor.set_resolution(0.25)                              # Set resolution, (celsius 0.5|0.25|0.125|0.0625) → None

# --- Program the healthy window and the door-open threshold ---
# TLOWER/TUPPER bracket normal operation; TCRIT flags a door left open.
# 3 °C of hysteresis stops the Alert chattering while the compressor cycles.
sensor.set_lower_limit(-25.0)                            # Set TLOWER, (celsius °C) → None
sensor.set_upper_limit(-15.0)                            # Set TUPPER, (celsius °C) → None
sensor.set_critical_limit(-5.0)                          # Set TCRIT, (celsius °C) → None
sensor.set_hysteresis(3.0)                               # Set hysteresis, (celsius 0|1.5|3.0|6.0) → None

# --- Route every boundary to the Alert pin as a latched interrupt ---
# Interrupt mode latches each crossing until clear_interrupt(), so a short
# excursion is never missed between two reads.
sensor.configure_alert(mode='all', output='interrupt',
                       polarity='active_low')            # Configure Alert, (mode='all', output='comparator', polarity='active_low') → None
sensor.enable_alert()                                    # Enable Alert output, () → None

alerts = 0


def on_alert(status):
    # --- Report which boundary tripped, then re-arm ---
    # The status mask is a live read of TA's boundary bits; an empty mask
    # means the temperature has come back inside the healthy window.
    global alerts
    alerts += 1
    t = sensor.read_temperature()                        # Read ambient temperature, () → float °C
    if status & SOURCE_CRITICAL:
        print('{:.2f} °C  CRITICAL — door open?'.format(t))
    elif status & SOURCE_UPPER:
        print('{:.2f} °C  too warm'.format(t))
    elif status & SOURCE_LOWER:
        print('{:.2f} °C  too cold'.format(t))
    else:
        print('{:.2f} °C  back in range'.format(t))
    sensor.clear_interrupt()                             # Clear interrupt-mode Alert, () → None


sensor.on_interrupt(on_alert)                            # Subscribe to Alert, (callback, int_pin=None) → None
print('monitoring, {:.2f} °C now'.format(sensor.read_temperature()))  # Read ambient temperature, () → float °C

while alerts < MAX_ALERTS:
    time.sleep(1)

# --- Shut down cleanly after the demo run ---
sensor.disable_alert()                                   # Disable Alert output, () → None
sensor.off_interrupt()                                   # Unsubscribe, () → None
