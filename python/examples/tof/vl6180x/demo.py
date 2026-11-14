from periph.connection.i2c_auto import I2CConnection
from periph.chips.tof.vl6180x import (
    VL6180XFull, SOURCE_RANGE, MODE_OUT_OF_WINDOW, MODE_NEW_SAMPLE_READY)
import time

connection = I2CConnection(0x29, reg_bytes=2)                            # Create I²C connection, (addr=0x29, reg_bytes=2) → Connection
sensor = VL6180XFull(connection)                                         # Create VL6180X driver, (connection) → None

# --- Identification ---
print('model 0x%02X revision %s' % (sensor.model_id(), sensor.revision()))  # () → int ; () → (int, int)

# --- Single-shot ranging at 5 Hz for 10 s ---
for _ in range(50):
    mm = sensor.read_range()                                             # Single-shot range, () → int mm
    print('%d mm' % mm)
    time.sleep(0.2)

# --- Continuous ranging at 50 ms: move a hand towards the sensor ---
sensor.start_continuous_range(50)                                        # Start continuous ranging, (period_ms) → None
for _ in range(40):
    mm = sensor.read_range_continuous()                                  # Wait for next sample, () → int mm
    print('%d mm' % mm)
sensor.stop_continuous_range()                                           # Stop continuous ranging, () → None
time.sleep(0.2)

# --- Out-of-window interrupt for 30-80 mm ---
sensor.set_range_thresholds(30, 80)                                      # Range thresholds, (low, high) → None
sensor.enable_interrupt(SOURCE_RANGE, MODE_OUT_OF_WINDOW)                # Enable an interrupt, (source, mode) → None
sensor.start_continuous_range(50)
for _ in range(50):
    if sensor.poll_interrupt() & SOURCE_RANGE:                           # Read and clear, () → int SOURCE_* mask
        print('range out of window')
    time.sleep(0.1)
sensor.stop_continuous_range()
time.sleep(0.2)
sensor.enable_interrupt(SOURCE_RANGE, MODE_NEW_SAMPLE_READY)

# --- Light meter at gain 1 and 20 ---
for code in (6, 0):
    sensor.set_als_gain(code)                                            # ALS gain code, (code) → None
    print('gain code %d: %.2f lux' % (code, sensor.read_lux()))          # () → float lux
sensor.set_als_gain(6)

# --- Interleaved ALS + range for 5 s ---
sensor.set_interleaved(True)                                             # Interleaved ALS + range, (on) → None
sensor.start_continuous_als(100)                                         # Start continuous ALS, (period_ms) → None
time.sleep(5)
sensor.stop_continuous_als()                                             # Stop continuous ALS, () → None
sensor.set_interleaved(False)

# --- Calibration and diagnostics ---
sensor.set_range_offset(0)                                               # Part-to-part offset, (mm) → None
sensor.set_crosstalk_rate(0.0)                                           # Crosstalk rate, (rate_mcps) → None
sensor.set_als_integration_time(100)                                     # ALS integration time, (ms) → None
sensor.set_als_scaler(1)                                                 # ALS result scaler, (scaler) → None
sensor.recalibrate_vhv()                                                 # Recalibrate VHV, () → None
print('als raw', sensor.read_als_raw(), 'offset', sensor.get_range_offset())  # () → int ; () → int mm
print(mm, sensor.read_range_status())                                    # Range and error code, () → (int, int)
print('history', sensor.read_history())                                  # History buffer, () → list[int]

# --- Change the device address and back (volatile) ---
sensor.set_address(0x30)                                                 # Change address, (address) → None
sensor = VL6180XFull(I2CConnection(0x30, reg_bytes=2))
print('on 0x30: model 0x%02X' % sensor.model_id())
sensor.set_address(0x29)
