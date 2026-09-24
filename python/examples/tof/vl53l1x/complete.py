"""Complete example for the VL53L1X — exercise every method in the public API.

Constructs the Full driver, takes single-shot measurements, reads the full
measurement record, switches distance mode and timing budget, runs timed
continuous ranging, changes the signal and sigma thresholds and the region
of interest, applies and restores offset and crosstalk compensation, runs
both calibration helpers (restoring the values afterwards), the temperature
update, distance thresholds and the interrupt API, and finally moves the
sensor to another I2C address and back to 0x29.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.tof.vl53l1x import (
    VL53L1XFull, I2C_ADDRESS, DISTANCE_MODE_SHORT, DISTANCE_MODE_LONG,
    SOURCE_NEW_SAMPLE_READY, SOURCE_IN_WINDOW)

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
sensor = VL53L1XFull(connection)                         # Create VL53L1X Full driver, (connection)
                                                         # runs init: boot poll, ID check, ULD default config

print('model 0x{:02X}'.format(sensor.model_id()))        # Read model ID, () → int
                                                         # IDENTIFICATION__MODEL_ID, always 0xEA
print('module 0x{:02X}'.format(sensor.module_type()))    # Read module type, () → int
                                                         # IDENTIFICATION__MODULE_TYPE, always 0xCC
print('revision 0x{:02X}'.format(sensor.revision_id()))  # Read revision ID, () → int
                                                         # mask revision, 0x10

d = sensor.distance()                                    # Measure distance, () → int mm
                                                         # single shot; blocks for about one timing budget
print('distance', d, 'mm, valid', sensor.range_valid())  # Check last measurement, () → bool
                                                         # mapped range status == 0
print('range status', sensor.range_status())             # Read last range status, () → int
                                                         # 0 = valid, 2 = signal fail, 4 = out of bounds
m = sensor.read_measurement()                            # Read result block, () → dict
                                                         # distance, status, signal/ambient MCPS, SPAD count
print('signal {:.2f} MCPS, ambient {:.2f} MCPS, {:.1f} SPADs'.format(
    m['signal_rate_mcps'], m['ambient_rate_mcps'], m['effective_spad_count']))

print('mode', sensor.distance_mode())                    # Read distance mode, () → str
                                                         # from PHASECAL_CONFIG__TIMEOUT_MACROP
sensor.set_distance_mode(DISTANCE_MODE_SHORT)            # Set distance mode, (mode) → None
                                                         # ~1.3 m, robust in sunlight; keeps the budget
print('budget', sensor.timing_budget(), 'us')            # Read timing budget, () → int µs
                                                         # decoded from the range timeout A register
sensor.set_timing_budget(33000)                          # Set timing budget, (budget_us µs) → None
                                                         # ULD table values 15000 (short only) … 500000
print('short mode', sensor.distance(), 'mm')             # Measure distance, () → int mm
                                                         # 33 ms single shot
sensor.set_distance_mode(DISTANCE_MODE_LONG)             # Set distance mode, (mode) → None
                                                         # back to up to 4 m in the dark
sensor.set_timing_budget(100000)                         # Set timing budget, (budget_us µs) → None
                                                         # default 100 ms

sensor.set_inter_measurement(200)                        # Set inter-measurement period, (period_ms ms) → None
                                                         # must be >= the timing budget
print('period', sensor.inter_measurement(), 'ms')        # Read inter-measurement period, () → int ms
                                                         # oscillator ticks scaled by the PLL calibration
sensor.start_continuous(200)                             # Start continuous ranging, (period_ms=0 ms) → None
                                                         # timed mode; 0 = as fast as the budget allows
for _ in range(5):
    print('continuous', sensor.read_continuous(), 'mm')  # Read next continuous result, () → int mm
                                                         # waits for data ready, then clears it
while not sensor.data_ready():                           # Check for a result, () → bool
    time.sleep(0.01)                                     # GPIO1 line asserted
print('record', sensor.read_measurement()['distance_mm'], 'mm')  # Read result block, () → dict
                                                         # non-blocking; clears the interrupt
sensor.stop_continuous()                                 # Stop continuous ranging, () → None
                                                         # does not wait for a running measurement
time.sleep(0.25)

print('signal limit', sensor.signal_rate_limit())        # Read signal-rate limit, () → float MCPS
                                                         # 9.7 fixed point, default 1.0
sensor.set_signal_rate_limit(0.5)                        # Set signal-rate limit, (limit_mcps MCPS) → None
                                                         # lower = longer range, more noise
sensor.set_signal_rate_limit(1.0)                        # Set signal-rate limit, (limit_mcps MCPS) → None
                                                         # restore the default
print('sigma', sensor.sigma_threshold(), 'mm')           # Read sigma threshold, () → int mm
                                                         # 14.2 fixed point, default 90
sensor.set_sigma_threshold(60)                           # Set sigma threshold, (sigma_mm mm) → None
                                                         # stricter repeatability filter
sensor.set_sigma_threshold(90)                           # Set sigma threshold, (sigma_mm mm) → None
                                                         # restore the default

print('optical centre', sensor.optical_center())         # Read optical-centre SPAD, () → int
                                                         # factory NVM value for this part's lens
sensor.set_roi(8, 8)                                     # Set ROI size, (width SPADs, height SPADs) → None
                                                         # 4–16 each; narrows the field of view
sensor.set_roi_center(sensor.optical_center())           # Set ROI centre, (spad) → None
                                                         # align the narrow ROI with the lens
print('roi', sensor.roi(), 'centre', sensor.roi_center())  # Read ROI size, () → (int, int); Read ROI centre, () → int
                                                         # (width, height) in SPADs
sensor.set_roi(16, 16)                                   # Set ROI size, (width SPADs, height SPADs) → None
                                                         # full array; re-centres on SPAD 199

original = sensor.offset()                               # Read range offset, () → float mm
                                                         # NVM factory value, 0.25 mm steps
sensor.set_offset(original - 5.0)                        # Set range offset, (offset_mm mm) → None
                                                         # volatile override, −1024.0 to 1023.75 mm
print('offset', sensor.offset(), 'mm')                   # Read range offset, () → float mm
                                                         # 13-bit two's complement × 0.25
sensor.set_crosstalk_compensation(0.01)                  # Set crosstalk compensation, (rate_mcps MCPS) → None
                                                         # per-SPAD rate, 7.9 kcps register
print('crosstalk', sensor.crosstalk_compensation())      # Read crosstalk compensation, () → float MCPS
                                                         # 0 = off
print('calibrated offset', sensor.calibrate_offset(140))  # Calibrate offset, (target_mm mm) → float mm
                                                         # 50 samples against a target at 140 mm; applies it
print('calibrated crosstalk', sensor.calibrate_crosstalk(600))  # Calibrate crosstalk, (target_mm mm) → float MCPS
                                                         # 50 samples against a target at 600 mm; applies it
sensor.set_offset(original)                              # Set range offset, (offset_mm mm) → None
                                                         # restore the factory value
sensor.set_crosstalk_compensation(0.0)                   # Set crosstalk compensation, (rate_mcps MCPS) → None
                                                         # compensation off

sensor.recalibrate()                                     # Run temperature update, () → None
                                                         # full VHV; after a > 8 °C change, not while ranging

sensor.set_interrupt_thresholds(100, 800)                # Set distance thresholds, (low_mm mm, high_mm mm) → None
                                                         # 1 mm resolution
print('thresholds', sensor.interrupt_thresholds())       # Read distance thresholds, () → (int mm, int mm)
                                                         # (low, high)
sensor.enable_interrupt(SOURCE_IN_WINDOW)                # Select interrupt source, (source) → None
                                                         # fires while 100 mm <= range <= 800 mm
sensor.disable_interrupt(SOURCE_IN_WINDOW)               # Disable interrupt source, (source) → None
                                                         # reverts to new-sample-ready (no disabled state)
sensor.enable_interrupt(SOURCE_NEW_SAMPLE_READY)         # Select interrupt source, (source) → None
                                                         # the default data-ready source


def on_sample(status):
    print('interrupt, source', status)


sensor.on_interrupt(on_sample)                           # Subscribe to GPIO1, (callback, int_pin=None) → None
                                                         # interrupt is cleared before the callback
sensor.start_continuous(200)                             # Start continuous ranging, (period_ms=0 ms) → None
                                                         # timed mode feeds the subscription
time.sleep(1)
sensor.stop_continuous()                                 # Stop continuous ranging, () → None
                                                         # no more samples
sensor.off_interrupt()                                   # Unsubscribe, () → None
                                                         # detaches the pin handler or stops the polling thread
print('pending', sensor.poll_interrupt())                # Read and clear interrupt, () → int
                                                         # active SOURCE_* value, 0 = nothing pending

sensor.set_address(0x30)                                 # Change I2C address, (address) → None
                                                         # volatile; this driver instance is now unusable
moved = VL53L1XFull(I2CConnection(i2c, 0x30))            # Create VL53L1X Full driver, (connection)
                                                         # re-init at the new address is safe
print('at 0x30', moved.distance(), 'mm')                 # Measure distance, () → int mm
                                                         # same sensor, new address
moved.set_address(I2C_ADDRESS)                           # Change I2C address, (address) → None
                                                         # back to the power-on 0x29
