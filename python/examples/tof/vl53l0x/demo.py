"""Demo for the VL53L0X — touchless presence gate with multi-rate ranging.

A first measurement decides the ranging profile: in a dark room (ambient
below 0.5 MCPS) the long-range profile is used, otherwise the default one.
Timed continuous ranging at 100 ms then feeds an out-of-window interrupt:
something closer than 10 cm is an ENTER event, the scene clearing beyond
80 cm a LEAVE event. After 20 events or 60 s, the demo switches back to
new-sample interrupts, prints statistics over 10 samples, stops ranging,
and recalibrates.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.connection.input_pin import MicroPythonPin
from periph.chips.tof.vl53l0x import (
    VL53L0XFull, I2C_ADDRESS, SOURCE_OUT_OF_WINDOW, SOURCE_NEW_SAMPLE_READY)

MAX_EVENTS = 20
MAX_SECONDS = 60
GPIO1_PIN = 4        # set to None when GPIO1 is not wired

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
int_pin = MicroPythonPin(Pin(GPIO1_PIN, Pin.IN, Pin.PULL_UP)) if GPIO1_PIN is not None else None
connection = I2CConnection(i2c, I2C_ADDRESS, int_pin=int_pin)
sensor = VL53L0XFull(connection)                         # Create VL53L0X Full driver, (connection)

# --- Pick a profile from the ambient light level ---
# The long-range profile (0.1 MCPS limit, 18/14 PCLK VCSEL periods) reaches
# ~2 m, but only without IR background; in daylight it mostly adds invalid
# readings. One single-shot measurement tells us how bright the scene is.
sensor.distance()                                        # Measure distance, () → int mm
first = sensor.read_measurement()                        # Read result block, () → dict
if first['ambient_rate_mcps'] < 0.5:
    sensor.set_profile('long_range')                     # Apply ranging profile, (profile) → None
    print('dark scene ({:.2f} MCPS ambient): long_range profile'.format(first['ambient_rate_mcps']))
else:
    sensor.set_profile('default')                        # Apply ranging profile, (profile) → None
    print('bright scene ({:.2f} MCPS ambient): default profile'.format(first['ambient_rate_mcps']))

# --- Arm the presence gate ---
# Timed ranging every 100 ms keeps the laser mostly idle. The firmware
# compares each result with the 100 mm / 800 mm window itself and only
# raises GPIO1 when a reading falls outside it.
sensor.set_interrupt_thresholds(100, 800)                # Set distance thresholds, (low_mm mm, high_mm mm) → None
sensor.enable_interrupt(SOURCE_OUT_OF_WINDOW)            # Select interrupt source, (source) → None
sensor.start_continuous(100)                             # Start continuous ranging, (period_ms=0 ms) → None

events = 0


def report(status):
    # --- Classify the event ---
    # The status is already cleared; the result block still holds the
    # measurement that triggered it.
    global events
    d = sensor.read_measurement()['distance_mm']         # Read result block, () → dict
    events += 1
    print('ENTER' if d < 100 else 'LEAVE', d, 'mm')


start = time.ticks_ms()
if int_pin is not None:
    sensor.on_interrupt(report)                          # Subscribe to GPIO1, (callback, int_pin=None) → None
while events < MAX_EVENTS and time.ticks_diff(time.ticks_ms(), start) < MAX_SECONDS * 1000:
    if int_pin is None:
        status = sensor.poll_interrupt()                 # Read and clear status, () → int
        if status:
            report(status)
    time.sleep(0.05)

# --- Statistics over fresh samples ---
# Threshold sources hide ordinary samples from data_ready(), so switch back
# to new-sample-ready before using the blocking continuous reads.
sensor.off_interrupt()                                   # Unsubscribe, () → None
sensor.enable_interrupt(SOURCE_NEW_SAMPLE_READY)         # Select interrupt source, (source) → None
sensor.poll_interrupt()                                  # Read and clear status, () → int
samples = []
rates = []
for _ in range(10):
    samples.append(sensor.read_continuous())             # Read next continuous result, () → int mm
    rates.append(sensor.read_measurement()['signal_rate_mcps'])  # Read result block, () → dict
print('mean {:.0f} mm, min {} mm, max {} mm, signal {:.2f} MCPS'.format(
    sum(samples) / len(samples), min(samples), max(samples), sum(rates) / len(rates)))

# --- Shut down and recalibrate ---
# Reference calibration must run in software standby. Repeat it whenever
# the sensor's temperature has drifted more than 8 °C.
sensor.stop_continuous()                                 # Stop continuous ranging, () → None
time.sleep(0.2)
sensor.recalibrate()                                     # Rerun reference calibration, () → None
print('done')
