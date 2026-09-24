"""Complete example for the VL53L0X — exercise every method in the public API.

Constructs the Full driver, takes single-shot measurements, reads the full
measurement record, runs back-to-back and timed continuous ranging, changes
the timing budget, signal-rate limit, VCSEL periods and profile, applies and
restores an offset and crosstalk compensation, recalibrates, sets distance
thresholds, drives the interrupt API, and finally moves the sensor to
another I2C address and back to 0x29.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.tof.vl53l0x import (
    VL53L0XFull, I2C_ADDRESS, PRE_RANGE, FINAL_RANGE,
    SOURCE_NEW_SAMPLE_READY, SOURCE_OUT_OF_WINDOW)

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
sensor = VL53L0XFull(connection)                         # Create VL53L0X Full driver, (connection)
                                                         # runs init: ID check, tuning, SPADs, VHV + phase calibration

print('model 0x{:02X}'.format(sensor.model_id()))        # Read model ID, () → int
                                                         # IDENTIFICATION_MODEL_ID, always 0xEE
print('revision 0x{:02X}'.format(sensor.revision_id()))  # Read revision ID, () → int
                                                         # IDENTIFICATION_REVISION_ID, 0x10 on current silicon

d = sensor.distance()                                    # Measure distance, () → int mm
                                                         # single shot; blocks for about one timing budget
print('distance', d, 'mm, valid', sensor.range_valid())  # Check last measurement, () → bool
                                                         # device range status == 11 (range complete)
print('range status', sensor.range_status())             # Read last range status, () → int 0–15
                                                         # 11 = valid, 4 = no target
m = sensor.read_measurement()                            # Read result block, () → dict
                                                         # distance, status, signal/ambient MCPS, SPAD count
print('signal {:.2f} MCPS, ambient {:.2f} MCPS, {:.1f} SPADs'.format(
    m['signal_rate_mcps'], m['ambient_rate_mcps'], m['effective_spad_count']))

sensor.start_continuous()                                # Start continuous ranging, (period_ms=0 ms) → None
                                                         # 0 = back-to-back measurements
for _ in range(5):
    print('continuous', sensor.read_continuous(), 'mm')  # Read next continuous result, () → int mm
                                                         # waits for a fresh data-ready, then clears it
sensor.stop_continuous()                                 # Stop continuous ranging, () → None
                                                         # does not wait for a running measurement

sensor.start_continuous(100)                             # Start continuous ranging, (period_ms=0 ms) → None
                                                         # timed mode: one measurement every 100 ms
while not sensor.data_ready():                           # Check for a result, () → bool
    time.sleep(0.01)                                     # RESULT_INTERRUPT_STATUS bits 2:0 non-zero
print('timed', sensor.read_measurement()['distance_mm'], 'mm')  # Read result block, () → dict
                                                         # non-blocking; clears the interrupt
sensor.stop_continuous()                                 # Stop continuous ranging, () → None
                                                         # back to software standby

print('budget', sensor.timing_budget(), 'us')            # Read timing budget, () → int µs
                                                         # computed from the sequence-step timeouts
sensor.set_timing_budget(50000)                          # Set timing budget, (budget_us µs) → None
                                                         # longer budget = lower noise, >= 20000 µs
print('signal limit', sensor.signal_rate_limit())        # Read signal-rate limit, () → float MCPS
                                                         # 9.7 fixed point
sensor.set_signal_rate_limit(0.1)                        # Set signal-rate limit, (limit_mcps MCPS) → None
                                                         # lower = longer range, more noise
sensor.set_vcsel_pulse_period(PRE_RANGE, 18)             # Set VCSEL period, (period_type, pclks) → None
                                                         # pre-range 12/14/16/18; redoes phase calibration
sensor.set_vcsel_pulse_period(FINAL_RANGE, 14)           # Set VCSEL period, (period_type, pclks) → None
                                                         # final-range 8/10/12/14
print('vcsel', sensor.vcsel_pulse_period(PRE_RANGE),     # Read VCSEL period, (period_type) → int PCLKs
      sensor.vcsel_pulse_period(FINAL_RANGE))            # (reg + 1) × 2
sensor.set_profile('default')                            # Apply ranging profile, (profile) → None
                                                         # 0.25 MCPS, 14/10 PCLKs, 33 ms

original = sensor.offset()                               # Read range offset, () → float mm
                                                         # NVM factory value, 0.25 mm steps
sensor.set_offset(original - 5.0)                        # Set range offset, (offset_mm mm) → None
                                                         # volatile override, −512.0 to 511.75 mm
print('offset', sensor.offset(), 'mm')                   # Read range offset, () → float mm
                                                         # 12-bit two's complement × 0.25
sensor.set_offset(original)                              # Set range offset, (offset_mm mm) → None
                                                         # restore the factory value
sensor.set_crosstalk_compensation(0.0)                   # Set crosstalk compensation, (rate_mcps MCPS) → None
                                                         # 0 = compensation off

sensor.recalibrate()                                     # Rerun reference calibration, () → None
                                                         # VHV + phase; needed after a > 8 °C change

sensor.set_interrupt_thresholds(100, 800)                # Set distance thresholds, (low_mm mm, high_mm mm) → None
                                                         # 2 mm resolution
print('thresholds', sensor.interrupt_thresholds())       # Read distance thresholds, () → (int mm, int mm)
                                                         # (low, high)
sensor.enable_interrupt(SOURCE_OUT_OF_WINDOW)            # Select interrupt source, (source) → None
                                                         # replaces the active source (mutually exclusive)
sensor.disable_interrupt(SOURCE_OUT_OF_WINDOW)           # Disable interrupt source, (source) → None
                                                         # only if it is the active one
sensor.enable_interrupt(SOURCE_NEW_SAMPLE_READY)         # Select interrupt source, (source) → None
                                                         # back to the default data-ready source


def on_sample(status):
    print('interrupt, source', status)


sensor.on_interrupt(on_sample)                           # Subscribe to GPIO1, (callback, int_pin=None) → None
                                                         # status is read and cleared before the callback
sensor.start_continuous(200)                             # Start continuous ranging, (period_ms=0 ms) → None
                                                         # timed mode feeds the subscription
time.sleep(1)
sensor.stop_continuous()                                 # Stop continuous ranging, () → None
                                                         # no more samples
sensor.off_interrupt()                                   # Unsubscribe, () → None
                                                         # detaches the pin handler or stops the polling thread
print('pending', sensor.poll_interrupt())                # Read and clear status, () → int
                                                         # SOURCE_* value that fired, 0 = nothing pending

sensor.set_address(0x30)                                 # Change I2C address, (address) → None
                                                         # volatile; this driver instance is now unusable
moved = VL53L0XFull(I2CConnection(i2c, 0x30))            # Create VL53L0X Full driver, (connection)
                                                         # re-init at the new address is safe
print('at 0x30', moved.distance(), 'mm')                 # Measure distance, () → int mm
                                                         # same sensor, new address
moved.set_address(I2C_ADDRESS)                           # Change I2C address, (address) → None
                                                         # back to the power-on 0x29
