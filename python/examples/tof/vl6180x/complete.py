from periph.connection.i2c_auto import I2CConnection
from periph.chips.tof.vl6180x import (
    VL6180XFull, SOURCE_RANGE, SOURCE_ALS, MODE_OUT_OF_WINDOW, MODE_NEW_SAMPLE_READY)
import time

connection = I2CConnection(0x29, reg_bytes=2)                            # Create I²C connection, (addr=0x29, reg_bytes=2) → Connection
sensor = VL6180XFull(connection)                                         # Create VL6180X driver, (connection) → None

print(sensor.read_range())                                               # Single-shot range, () → int mm
                                                                         # raises RuntimeError on a non-zero range error code
print(sensor.read_range_status())                                        # Range and error code, () → (int mm, int error)
                                                                         # never raises on a range error; 0 = valid
print(sensor.read_als_raw())                                             # Single-shot ALS count, () → int
print(sensor.read_lux())                                                 # Single-shot ALS in lux, () → float lux
                                                                         # 0.32 lux/count at gain 1, 100 ms

sensor.start_continuous_range(50)                                        # Start continuous ranging, (period_ms) → None
                                                                         # 10-2550 ms in 10 ms steps
print(sensor.read_range_continuous())                                    # Wait for next sample, () → int mm
sensor.stop_continuous_range()                                           # Stop continuous ranging, () → None
sensor.start_continuous_als(100)                                         # Start continuous ALS, (period_ms) → None
sensor.stop_continuous_als()                                             # Stop continuous ALS, () → None
sensor.set_interleaved(False)                                            # Interleaved ALS + range, (on) → None

sensor.set_als_gain(6)                                                   # ALS gain code, (code) → None
                                                                         # 0 = 20 ... 6 = 1, 7 = 40
sensor.set_als_integration_time(100)                                     # ALS integration time, (ms) → None
                                                                         # 1-512 ms
sensor.set_als_scaler(1)                                                 # ALS result scaler, (scaler) → None
sensor.set_range_thresholds(30, 80)                                      # Range thresholds, (low, high) → None
sensor.set_als_thresholds(100, 5000)                                     # ALS thresholds, (low, high) → None
sensor.set_max_convergence_time(49)                                      # Max convergence time, (ms) → None
sensor.set_averaging_period(48)                                          # Averaging period, (units) → None
sensor.set_range_offset(0)                                               # Part-to-part offset, (mm) → None
print(sensor.get_range_offset())                                         # Read the offset, () → int mm
sensor.set_crosstalk_rate(0.0)                                           # Crosstalk rate, (rate_mcps) → None
sensor.recalibrate_vhv()                                                 # Recalibrate VHV, () → None
print(sensor.model_id(), sensor.revision())                              # Identification, () → int ; () → (int, int)
                                                                         # model ID 0xB4
print(sensor.read_history())                                             # History buffer, () → list[int]

sensor.enable_interrupt(SOURCE_RANGE, MODE_OUT_OF_WINDOW)                # Enable an interrupt, (source, mode) → None
print(sensor.poll_interrupt())                                           # Read and clear, () → int SOURCE_* mask
sensor.on_interrupt(lambda status: print('irq', status))                 # Subscribe, (callback, int_pin=None) → None
time.sleep(0.1)
sensor.off_interrupt()                                                   # Unsubscribe, () → None
sensor.disable_interrupt(SOURCE_RANGE)                                   # Disable an interrupt, (source) → None
sensor.enable_interrupt(SOURCE_RANGE, MODE_NEW_SAMPLE_READY)
sensor.enable_interrupt(SOURCE_ALS, MODE_NEW_SAMPLE_READY)
# sensor.set_address(0x30)                                               # Change address (volatile), (address) → None
