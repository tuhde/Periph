"""Demo for the PCF8523 — the scheduling core of a battery-backed logger.

Reseeds the clock after a power loss, checks the coin cell, then wakes on
an hourly alarm to print a timestamp while Timer B pulses a 30-second
"still running" heartbeat on INT2 that toggles an LED.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.rtc.pcf8523 import (
    PCF8523Full, I2C_ADDRESS, SOURCE_ALARM, SOURCE_TIMER_B,
)

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
rtc = PCF8523Full(connection)                            # Create PCF8523 Full driver, (connection)
led = Pin(2, Pin.OUT)

# --- Detect a lost time reference and reseed if needed ---
# A fresh chip, or one whose backup cell was disconnected too long, reports
# the OS flag set: its calendar cannot be trusted until it is reseeded.
if rtc.oscillator_stopped():                             # Query oscillator-stop flag, () → bool
    rtc.set_datetime(2026, 1, 1, 4, 0, 0, 0)             # Write calendar clock, (year, month, day, weekday 0=Sun, hour, minute, second) → None
    print('oscillator was stopped - reseeded from reference timestamp')

# --- Keep the clock alive through power cuts ---
# Standard switch-over is already the driver default; it is repeated here
# so the logger's power policy is explicit. A low coin cell is reported
# once so it can be replaced before the next outage.
rtc.configure_battery_backup('standard')                 # Select battery switch-over, (mode, low_detection=True) → None
if rtc.is_battery_low():                                 # Query battery-low flag, () → bool
    print('warning: backup battery low - replace the coin cell')

# --- Hourly wake-up plus a 30 s heartbeat ---
# Only the minute field is enabled, so the alarm matches at hh:00 every
# hour. Timer B reloads automatically and has its own INT2 pin, so the
# heartbeat keeps running independently of the hourly alarm.
rtc.disable_clock_output()                               # Disable CLKOUT, () → None
rtc.set_alarm(minute=0)                                  # Configure alarm, (minute=None, hour=None, day=None, weekday=None) → None
rtc.configure_timer_b(30, '1hz')                         # Start Timer B, (value 0–255, source_clock, pulse_width_ms=46.875 ms, pulsed=False) → None

alarms = [0]


def on_event(status):
    # --- Dispatch by source: log on the alarm, blink on the heartbeat ---
    if status & SOURCE_ALARM:
        year, month, day, weekday, hour, minute, second = rtc.get_datetime()  # Read calendar clock, () → (year, month, day, weekday, hour, minute, second)
        print('[hourly] {:04d}-{:02d}-{:02d} {:02d}:{:02d}:{:02d}'.format(
            year, month, day, hour, minute, second))
        alarms[0] += 1
    if status & SOURCE_TIMER_B:
        led.value(not led.value())


rtc.on_interrupt(on_event)                               # Subscribe to interrupts, (callback, int_pin=None) → None
rtc.enable_interrupt(SOURCE_ALARM | SOURCE_TIMER_B)      # Enable sources, (source) → None

while alarms[0] < 3:
    time.sleep_ms(200)

# --- Leave the chip quiet on exit ---
rtc.disable_interrupt(SOURCE_ALARM | SOURCE_TIMER_B)     # Disable sources, (source) → None
rtc.disable_timer_b()                                    # Stop Timer B, () → None
rtc.off_interrupt()                                      # Unsubscribe, () → None
