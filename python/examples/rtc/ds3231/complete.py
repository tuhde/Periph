"""Complete example for the DS3231 — exercise every method in the public API.

Constructs the Full driver, sets and reads the calendar clock, configures
both alarms, subscribes to alarm interrupts, drives the square-wave and
32kHz outputs, and reads/writes the oscillator and aging-offset controls.
"""

from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.rtc.ds3231 import (
    DS3231Full, I2C_ADDRESS,
    ALARM1_MATCH_HOURS_MINUTES_SECONDS, ALARM2_EVERY_MINUTE,
    SOURCE_ALARM1, SOURCE_ALARM2,
)

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
rtc = DS3231Full(connection)                             # Create DS3231 Full driver, (connection)

rtc.set_datetime(2026, 9, 22, 2, 14, 30, 0)              # Write calendar clock, (year, month, day, weekday, hour, minute, second) → None
                                                          # also forces 24-hour mode and clears the Oscillator Stop Flag
dt = rtc.get_datetime()                                  # Read calendar clock, () → (year, month, day, weekday, hour, minute, second)
                                                          # decodes the seven BCD clock/calendar registers
temp_c = rtc.read_temperature()                          # Read last temperature conversion, () → float C
                                                          # no forced conversion; may be up to 64 s stale

rtc.force_temperature_conversion()                       # Force a fresh conversion, () → None
                                                          # sets CONV and blocks until BSY clears (max 200 ms)

rtc.set_alarm1(0, 30, 9, 0, False, ALARM1_MATCH_HOURS_MINUTES_SECONDS)  # Configure Alarm 1, (second, minute, hour, day_or_date, is_day_of_week, match_mode) → None
                                                          # fires when hours, minutes, seconds all match
alarm1 = rtc.get_alarm1()                                 # Read Alarm 1 configuration, () → dict
                                                          # decodes the mask bits back into match_mode

rtc.set_alarm2(0, 0, 0, False, ALARM2_EVERY_MINUTE)      # Configure Alarm 2, (minute, hour, day_or_date, is_day_of_week, match_mode) → None
                                                          # fires once per minute at :00 seconds
alarm2 = rtc.get_alarm2()                                 # Read Alarm 2 configuration, () → dict


def on_alarm(status):
    print('alarm status=0x{:02X}'.format(status))


rtc.on_interrupt(on_alarm)                                # Subscribe to alarm interrupts, (callback, int_pin=None) → None
                                                          # sets INTCN=1 so INT/SQW carries alarm interrupts
rtc.enable_interrupt(SOURCE_ALARM1 | SOURCE_ALARM2)      # Enable alarm sources, (source) → None
status = rtc.poll_interrupt()                             # Poll & clear alarm flags, () → int
                                                          # clears A1F/A2F only, leaves OSF/EN32kHz/BSY untouched
rtc.disable_interrupt(SOURCE_ALARM1)                      # Disable one alarm source, (source) → None
rtc.off_interrupt()                                       # Unsubscribe, () → None

rtc.enable_square_wave(8192, False)                       # Enable square wave, (rate_hz=8192, battery_backed=False) → None
                                                          # sets INTCN=0 - mutually exclusive with alarm interrupts
rtc.disable_square_wave()                                 # Return INT/SQW to interrupt mode, () → None

rtc.enable_32khz_output()                                 # Enable 32kHz output, () → None
en32 = rtc.is_32khz_enabled()                              # Query 32kHz output, () → bool
rtc.disable_32khz_output()                                # Disable 32kHz output, () → None

stopped = rtc.oscillator_stopped()                         # Query Oscillator Stop Flag, () → bool
                                                          # true means timekeeping data may be invalid
rtc.clear_oscillator_stopped()                             # Clear Oscillator Stop Flag, () → None

rtc.enable_battery_oscillator()                            # Keep oscillator running on VBAT, () → None
rtc.disable_battery_oscillator()                           # Stop oscillator on VBAT, () → None
                                                          # saves battery current between power cycles

rtc.set_aging_offset(-5)                                   # Write oscillator trim code, (offset) → None
aging = rtc.get_aging_offset()                              # Read oscillator trim code, () → int
                                                          # raw signed trim value, no fixed physical scale

print(dt, '{:.2f} C'.format(temp_c))
print('alarm1', alarm1, 'alarm2', alarm2)
print('status=0x{:02X} 32khz={} osf={} aging={}'.format(status, en32, stopped, aging))
