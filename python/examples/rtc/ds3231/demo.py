"""Demo for the DS3231 — a backup-clock module for a data logger.

Reseeds the clock after a power loss, then logs a "reading" on every
once-per-minute and once-per-hour alarm match, using the on-chip
temperature sensor as the payload.
"""

from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.rtc.ds3231 import (
    DS3231Full, I2C_ADDRESS,
    ALARM1_MATCH_SECONDS, ALARM2_MATCH_MINUTES,
    SOURCE_ALARM1, SOURCE_ALARM2,
)

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
rtc = DS3231Full(connection)

# --- Detect a lost power reference and reseed if needed ---
# A fresh chip, or one whose backup coin cell died, reports the
# Oscillator Stop Flag set — its clock/calendar registers cannot be
# trusted until reseeded from a known-good reference.
if rtc.oscillator_stopped():
    rtc.set_datetime(2026, 1, 1, 4, 0, 0, 0)  # also clears the Oscillator Stop Flag
    print('oscillator was stopped - reseeded from reference timestamp')

logged = [0]


def on_alarm(status):
    # --- Log every match; readings pair a timestamp with a temperature ---
    year, month, day, weekday, hour, minute, second = rtc.get_datetime()
    temp_c = rtc.read_temperature()
    if status & SOURCE_ALARM1:
        print('[minute] {:02d}:{:02d}:{:02d}  {:.2f} C'.format(hour, minute, second, temp_c))
        logged[0] += 1
    if status & SOURCE_ALARM2:
        print('[hourly] {:02d}:{:02d}  {:.2f} C'.format(hour, minute, temp_c))


# --- Arm a once-per-minute log tick and a once-per-hour summary tick ---
# Both alarms repeat automatically (their registers are never rewritten),
# so once armed, no further configuration is needed between matches.
rtc.set_alarm1(0, 0, 0, 0, False, ALARM1_MATCH_SECONDS)  # fires once per minute at :00 seconds
rtc.set_alarm2(0, 0, 0, False, ALARM2_MATCH_MINUTES)      # fires once per hour at :00 minutes

rtc.on_interrupt(on_alarm)
rtc.enable_interrupt(SOURCE_ALARM1 | SOURCE_ALARM2)

import time
while logged[0] < 5:
    time.sleep_ms(200)

rtc.disable_interrupt(SOURCE_ALARM1 | SOURCE_ALARM2)
rtc.off_interrupt()
