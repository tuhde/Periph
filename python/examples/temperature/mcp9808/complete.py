"""Complete example for the MCP9808 — exercise every method in the public API.

Constructs the Full driver, reads the temperature, changes the resolution,
enters and leaves Shutdown mode, programs all three boundaries and the
hysteresis, configures and enables the Alert output, polls and clears the
boundary status, and subscribes to Alert events. The one-way lock methods
are shown but left commented out — they cannot be undone without a
power-on reset.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.temperature.mcp9808 import (
    MCP9808Full, I2C_ADDRESS, SOURCE_LOWER, SOURCE_UPPER, SOURCE_CRITICAL)

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
sensor = MCP9808Full(connection)                         # Create MCP9808 Full driver, (connection)
                                                         # checks MANUFACTURER_ID 0x0054 and DEVICE_ID 0x04

t = sensor.read_temperature()                            # Read ambient temperature, () → float °C
                                                         # masks TA's 3 status bits, decodes 1/16 °C two's complement
print('temperature {:.4f} °C'.format(t))

sensor.set_resolution(0.25)                              # Set resolution, (celsius 0.5|0.25|0.125|0.0625) → None
                                                         # 0.25 °C step converts in ~65 ms instead of 250 ms
print('resolution', sensor.get_resolution(), '°C')       # Read resolution, () → float °C
                                                         # decodes the RESOLUTION register code

sensor.shutdown()                                        # Enter Shutdown mode, () → None
                                                         # stops conversion; TA keeps its last value
print('shutdown', sensor.is_shutdown())                  # Check Shutdown mode, () → bool
                                                         # reads CONFIG.SHDN
sensor.wake()                                            # Leave Shutdown mode, () → None
                                                         # resumes continuous conversion
time.sleep(0.1)

sensor.set_upper_limit(30.0)                             # Set TUPPER, (celsius °C) → None
                                                         # rounded to the nearest 0.25 °C step
sensor.set_lower_limit(10.0)                             # Set TLOWER, (celsius °C) → None
                                                         # rounded to the nearest 0.25 °C step
sensor.set_critical_limit(45.0)                          # Set TCRIT, (celsius °C) → None
                                                         # rounded to the nearest 0.25 °C step
print('upper', sensor.get_upper_limit())                 # Read TUPPER, () → float °C
                                                         # decodes the 0.25 °C two's-complement boundary
print('lower', sensor.get_lower_limit())                 # Read TLOWER, () → float °C
                                                         # decodes the 0.25 °C two's-complement boundary
print('critical', sensor.get_critical_limit())           # Read TCRIT, () → float °C
                                                         # decodes the 0.25 °C two's-complement boundary

sensor.set_hysteresis(1.5)                               # Set hysteresis, (celsius 0|1.5|3.0|6.0) → None
                                                         # applied on the cooling edge of each boundary only
print('hysteresis', sensor.get_hysteresis())             # Read hysteresis, () → float °C
                                                         # decodes CONFIG.THYST

# sensor.lock_critical_limit()                           # Lock TCRIT, () → None
#                                                        # irreversible until power-on reset
# sensor.lock_window_limits()                            # Lock TUPPER/TLOWER, () → None
#                                                        # irreversible until power-on reset
print('crit locked', sensor.is_critical_limit_locked())  # Check TCRIT lock, () → bool
                                                         # reads CONFIG.CRIT_LOCK
print('win locked', sensor.is_window_limits_locked())    # Check TUPPER/TLOWER lock, () → bool
                                                         # reads CONFIG.WIN_LOCK

sensor.configure_alert(mode='all', output='interrupt',
                       polarity='active_low')            # Configure Alert, (mode='all', output='comparator', polarity='active_low') → None
                                                         # sets ALERT_SEL, ALERT_MOD and ALERT_POL together
sensor.enable_alert()                                    # Enable Alert output, () → None
                                                         # sets CONFIG.ALERT_CNT
print('alert asserted', sensor.is_alert_asserted())      # Check Alert output, () → bool
                                                         # reads the read-only CONFIG.ALERT_STAT

status = sensor.poll_interrupt()                         # Read boundary status, () → int mask
                                                         # TA's live bits: SOURCE_LOWER/UPPER/CRITICAL, nothing cleared
print('below lower', bool(status & SOURCE_LOWER),
      'above upper', bool(status & SOURCE_UPPER),
      'critical', bool(status & SOURCE_CRITICAL))
sensor.clear_interrupt()                                 # Clear interrupt-mode Alert, () → None
                                                         # writes CONFIG.INT_CLEAR=1; no effect in comparator mode


def on_alert(status):
    print('alert, status mask', status)


sensor.on_interrupt(on_alert)                            # Subscribe to Alert, (callback, int_pin=None) → None
                                                         # callback receives the poll_interrupt() mask
time.sleep(5)
sensor.off_interrupt()                                   # Unsubscribe, () → None
                                                         # detaches the pin handler or stops the polling thread
sensor.disable_alert()                                   # Disable Alert output, () → None
                                                         # clears CONFIG.ALERT_CNT
