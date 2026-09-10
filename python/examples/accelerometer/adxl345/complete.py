"""Complete example for the ADXL345 — exercise every method in the public API.

Constructs the Full driver, reconfigures range / data rate / FIFO, runs a
short offset calibration against gravity, configures single-tap detection,
prints samples from the FIFO, and reads the interrupt-source register.
"""

from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.accelerometer.adxl345 import ADXL345Full

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, 0x53)
accel = ADXL345Full(connection)                         # Create ADXL345 Full driver, (connection, bus_type='i2c')

accel.set_range(4)                                      # Set measurement range, (range_g) → None g
                                                         # selects ±4 g; FULL_RES is preserved so scale stays 3.9 mg/LSB
accel.set_data_rate(200)                                # Set output data rate, (rate_hz) → None Hz
                                                         # picks the nearest supported value (200 Hz in this case)
accel.set_low_power(False)                              # Set low-power mode, (enabled) → None
                                                         # normal-power mode; LOW_POWER bit in BW_RATE cleared
accel.calibrate_offset(samples=64)                      # Calibrate offsets, (target_x=0 g, target_y=0 g, target_z=1 g, samples=64) → None
                                                         # averages 64 samples with Z axis up and writes OFSX/OFSY/OFSZ
accel.set_tap_detection(0.5, 10)                        # Configure single-tap, (threshold_g, duration_ms, axes=0x07, suppress=False) → None g, ms
                                                         # 0.5 g threshold, 10 ms duration, all axes, no suppress
accel.set_double_tap(50, 200)                           # Configure double-tap, (latency_ms, window_ms) → None ms, ms
                                                         # 50 ms latency, 200 ms window between taps
accel.set_fifo_mode(ADXL345Full.FIFO_STREAM, 16)        # Configure FIFO, (mode, samples=16) → None
                                                         # stream mode, watermark 16 entries
accel.set_interrupt(ADXL345Full.INT_WATERMARK, True, 1) # Configure interrupt, (source, enabled, pin=1) → None
                                                         # enable watermark interrupt on INT1

x, y, z = accel.read()                                  # Read 3-axis acceleration, () → tuple(float, float, float) g
                                                         # single-shot burst read of all 6 data bytes
samples = accel.read_fifo()                             # Drain the FIFO, () → list[tuple(float, float, float)] g
                                                         # returns up to 32 (x, y, z) samples in *g*
count = accel.fifo_count()                              # FIFO entries available, () → int
                                                         # from FIFO_STATUS register
src = accel.read_interrupt_source()                     # Read interrupt source, () → int
                                                         # bitmask of currently active INT_* sources; clears latches

accel.self_test(False)                                  # Toggle self-test, (enabled) → None
                                                         # SELF_TEST bit in DATA_FORMAT cleared
accel.set_sleep(False)                                  # Set sleep mode, (enabled, wakeup_hz=8) → None Hz
                                                         # wake up; no further state changes
accel.set_link_mode(False)                              # Set activity/inactivity link, (enabled) → None
                                                         # Link bit in POWER_CTL cleared
accel.set_auto_sleep(False)                             # Set auto-sleep, (enabled) → None
                                                         # AUTO_SLEEP bit cleared