"""Complete example for the TMP117 — exercise every method in the public API.

Constructs the Full driver, reads the temperature, changes the conversion
mode, averaging and cycle time, takes a one-shot reading, programs both
limits and the calibration offset, configures the ALERT output, reads the
EEPROM scratch registers, polls the alert flags, subscribes to ALERT events,
and finally soft-resets the sensor. EEPROM unlock/lock is shown without any
write in between, so no power-on default is changed.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.temperature.tmp117 import (
    TMP117Full, I2C_ADDRESS, SOURCE_HIGH, SOURCE_LOW)

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
sensor = TMP117Full(connection)                          # Create TMP117 Full driver, (connection)
                                                         # checks DEVICE_ID bits 11:0 == 0x117

t = sensor.read_temperature()                            # Read temperature, () → float °C
                                                         # decodes TEMP_RESULT, 0.0078125 °C two's complement
print('temperature {:.4f} °C'.format(t))

sensor.configure(mode='continuous', averaging=32,
                 cycle_seconds=0.5)                      # Configure conversion, (mode='continuous', averaging=8, cycle_seconds=1.0 s) → None
                                                         # writes MOD/AVG/CONV; cycle snaps to the nearest CONV step
print('config', sensor.get_config())                     # Read conversion config, () → (str, int, float s)
                                                         # decodes MOD, AVG and CONV from CONFIGURATION

sensor.configure(mode='shutdown')                        # Configure conversion, (mode='continuous', averaging=8, cycle_seconds=1.0 s) → None
                                                         # MOD=01 stops conversions; TEMP_RESULT keeps its last value
print('shutdown', sensor.is_shutdown())                  # Check Shutdown mode, () → bool
                                                         # reads MOD[1:0] == 01
sensor.trigger_one_shot()                                # Start one conversion, () → None
                                                         # MOD=11; returns to Shutdown when done
while not sensor.is_data_ready():                        # Check for a fresh result, () → bool
    time.sleep(0.01)                                     # reading Data_Ready clears it
print('one-shot {:.4f} °C'.format(sensor.read_temperature()))  # Read temperature, () → float °C
                                                         # the one-shot result
sensor.configure()                                       # Configure conversion, (mode='continuous', averaging=8, cycle_seconds=1.0 s) → None
                                                         # back to the POR default

sensor.set_high_limit(30.0)                              # Set THIGH_LIMIT, (celsius °C) → None
                                                         # rounded to the nearest 0.0078125 °C step
sensor.set_low_limit(10.0)                               # Set TLOW_LIMIT, (celsius °C) → None
                                                         # rounded to the nearest 0.0078125 °C step
print('high', sensor.get_high_limit())                   # Read THIGH_LIMIT, () → float °C
                                                         # same format as TEMP_RESULT
print('low', sensor.get_low_limit())                     # Read TLOW_LIMIT, () → float °C
                                                         # same format as TEMP_RESULT

sensor.set_temperature_offset(0.25)                      # Set calibration offset, (celsius °C) → None
                                                         # added to every result after linearization
print('offset', sensor.get_temperature_offset())         # Read calibration offset, () → float °C
                                                         # decodes TEMP_OFFSET
sensor.set_temperature_offset(0.0)                       # Set calibration offset, (celsius °C) → None
                                                         # remove the offset again

sensor.unlock_eeprom()                                   # Unlock EEPROM, () → None
                                                         # EUN=1: EEPROM-backed writes now persist
print('eeprom busy', sensor.is_eeprom_busy())            # Check EEPROM busy, () → bool
                                                         # reads EEPROM_UL.EEPROM_Busy
sensor.lock_eeprom()                                     # Lock EEPROM, () → None
                                                         # EUN=0: writes are volatile again
print('eeprom1 0x{:04X}'.format(sensor.read_eeprom_scratch(1)))  # Read EEPROM scratch, (slot 1|2|3) → int
                                                         # slot 1 holds part of the factory unique ID
sensor.write_eeprom_scratch(2, 0x1234)                   # Write EEPROM scratch, (slot 2, value 16-bit) → None
                                                         # only EEPROM2 is writable; volatile while locked
print('eeprom2 0x{:04X}'.format(sensor.read_eeprom_scratch(2)))  # Read EEPROM scratch, (slot 1|2|3) → int
                                                         # reads back EEPROM2

sensor.configure_alert(mode='alert', polarity='active_low',
                       pin_function='alert')             # Configure ALERT, (mode='alert', polarity='active_low', pin_function='alert') → None
                                                         # sets T/nA, POL and DR/Alert together

status = sensor.poll_interrupt()                         # Read alert flags, () → int mask
                                                         # HIGH_Alert/LOW_Alert; the read clears them in Alert mode
print('above high', bool(status & SOURCE_HIGH),
      'below low', bool(status & SOURCE_LOW))


def on_alert(status):
    print('alert, status mask', status)


sensor.on_interrupt(on_alert)                            # Subscribe to ALERT, (callback, int_pin=None) → None
                                                         # callback receives the poll_interrupt() mask
time.sleep(5)
sensor.off_interrupt()                                   # Unsubscribe, () → None
                                                         # detaches the pin handler or stops the polling thread

sensor.reset()                                           # Software reset, () → None
                                                         # reloads CONFIGURATION/limits/offset from EEPROM, 2 ms
