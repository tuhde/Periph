"""Demo for the PCF85063A — the scheduling core of a battery-powered logger.

Reseeds the clock after a power loss, counts boots in the free RAM byte,
then wakes on an hourly alarm to print a timestamp while a 30-second pulsed
countdown timer toggles an LED as a "still running" heartbeat.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.rtc.pcf85063a import (
    PCF85063AFull, I2C_ADDRESS, SOURCE_ALARM, SOURCE_TIMER,
)

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
rtc = PCF85063AFull(connection)                          # Create PCF85063A Full driver, (connection)
led = Pin(2, Pin.OUT)

# --- Detect a lost time reference and reseed if needed ---
# A fresh chip, or one whose supply dropped out, reports the OS flag set:
# its calendar cannot be trusted until it is reseeded. The crystal needs up
# to 2 s to start, so wait until the flag stays clear.
if rtc.oscillator_stopped():                             # Query oscillator-stop flag, () → bool
    rtc.set_datetime(2026, 1, 1, 4, 0, 0, 0)             # Write calendar clock, (year, month, day, weekday 0=Sun, hour, minute, second) → None
    print('oscillator was stopped - reseeded from reference timestamp')
    while rtc.oscillator_stopped():                      # Query oscillator-stop flag, () → bool
        time.sleep_ms(100)

# --- Count boots in the free RAM byte ---
# The RAM byte survives as long as VDD is present, so it works as a tiny
# non-volatile counter next to the clock.
boots = (rtc.read_ram() + 1) & 0xFF                      # Read RAM byte, () → int
rtc.write_ram(boots)                                     # Write RAM byte, (value 0–255) → None
print('boot #{}'.format(boots), rtc.get_datetime())      # Read calendar clock, () → (year, month, day, weekday, hour, minute, second)
print('offset', rtc.get_offset())                        # Read offset calibration, () → (int, str)

# --- Tune the board for low power ---
# Match the crystal's load capacitance and switch the 32.768 kHz CLKOUT
# off — it is enabled at power-on and costs current on a battery device.
rtc.set_oscillator_load(12.5)                            # Select crystal load, (picofarads 7|12.5) → None
rtc.disable_clock_output()                               # Disable CLKOUT, () → None

# --- Hourly wake-up plus a 30 s heartbeat ---
# Only second and minute are enabled, so the alarm matches at hh:00:00
# every hour. The pulsed timer reloads automatically; minute ticks share
# the same TF flag and are reported as SOURCE_TIMER.
rtc.set_alarm(minute=0, second=0)                        # Configure alarm, (second=None, minute=None, hour=None, day=None, weekday=None) → None
rtc.configure_timer(30, '1hz', pulsed=True)              # Start countdown timer, (value 1–255, source_clock, pulsed=False) → None
rtc.set_minute_interrupt('minute')                       # Select minute interrupt, (mode "off"|"minute"|"half_minute") → None

alarms = [0]


def on_event(status):
    # --- Dispatch by source: log on the alarm, blink on the heartbeat ---
    if status & SOURCE_ALARM:
        year, month, day, weekday, hour, minute, second = rtc.get_datetime()  # Read calendar clock, () → (year, month, day, weekday, hour, minute, second)
        print('[hourly] {:04d}-{:02d}-{:02d} {:02d}:{:02d}:{:02d}'.format(
            year, month, day, hour, minute, second))
        alarms[0] += 1
    if status & SOURCE_TIMER:
        led.value(not led.value())


rtc.enable_interrupt(SOURCE_ALARM | SOURCE_TIMER)        # Enable sources, (source) → None
rtc.on_interrupt(on_event)                               # Subscribe to interrupts, (callback, int_pin=None) → None

deadline = time.ticks_add(time.ticks_ms(), 5 * 60 * 1000)
while alarms[0] < 5 and time.ticks_diff(deadline, time.ticks_ms()) > 0:
    time.sleep_ms(200)

# --- Leave the chip quiet on exit ---
rtc.disable_interrupt(SOURCE_ALARM | SOURCE_TIMER)       # Disable sources, (source) → None
rtc.disable_timer()                                      # Stop timer, () → None
rtc.set_minute_interrupt('off')                          # Select minute interrupt, (mode "off"|"minute"|"half_minute") → None
rtc.off_interrupt()                                      # Unsubscribe, () → None
