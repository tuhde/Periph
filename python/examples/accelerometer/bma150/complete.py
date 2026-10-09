"""Complete example for the BMA150 — exercise every method in the public API.

Configures the range, bandwidth, raw reading, temperature, low-g, high-g,
any-motion, alert and latched-interrupt paths, plus sleep, wake-up,
soft-reset, self-test, version and customer scratch bytes.
"""

from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.accelerometer.bma150 import BMA150Full

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, 0x38)
accel = BMA150Full(connection)                             # Create BMA150 Full driver, (connection)

accel.set_range(8)                                        # Set measurement range, (range_g) → None g
                                                          # selects ±8 g; LSB scale changes from 256 to 64 LSB/g
accel.set_bandwidth(190)                                  # Set bandwidth, (bandwidth_hz) → None Hz
                                                          # picks nearest valid value (190 Hz in this case)
raw = accel.read_raw()                                    # Read raw 10-bit counts, () → tuple(int, int, int) LSB
                                                          # signed 10-bit two's-complement acceleration counts
temp = accel.read_temperature()                           # Read temperature, () → float °C
                                                          # 0.5 °C/LSB, 0x00 maps to −30 °C
ready = accel.new_data_available()                        # Check new data, () → bool
                                                          # True once all three new_data_X/Y/Z bits are set
accel.set_shadow(False)                                   # Set shadow mode, (enabled) → None
                                                          # keep LSB-then-MSB ordering (shadow_dis=0)

accel.set_low_g(0.4, 40)                                  # Configure low-g, (threshold_g, duration_ms, hysteresis_g=0, counter=0) → None g, ms
                                                          # 0.4 g threshold, 40 ms duration; enables SOURCE_LOW_G
accel.set_high_g(4.0, 2)                                  # Configure high-g, (threshold_g, duration_ms, hysteresis_g=0, counter=0) → None g, ms
                                                          # 4.0 g threshold, 2 ms duration; enables SOURCE_HIGH_G
accel.set_any_motion(0.5, samples=3)                      # Configure any-motion, (threshold_g, samples=1) → None g, samples
                                                          # 0.5 g threshold, 3 consecutive samples; enables SOURCE_ANY_MOTION
accel.set_alert(False)                                    # Toggle alert mode, (enabled) → None
                                                          # mutually exclusive with any-motion; not used here
accel.set_latch(True)                                     # Set latched interrupts, (enabled) → None
                                                          # latched until clear_interrupt(); latch_INT=1

status = accel.poll_interrupt()                           # Read STATUS, () → int
                                                          # STATUS byte; does not clear latched bits
accel.clear_interrupt()                                   # Clear latched interrupts, () → None
                                                          # writes reset_INT to CTRL (cleared on next sample)

accel.set_wake_up(True, pause_ms=80)                      # Set self-wake-up, (enabled, pause_ms=20) → None ms
                                                          # 80 ms sleep portion of the cycle, enable_adv_INT not required

x, y, z = accel.read()                                    # Read 3-axis acceleration, () → tuple(float, float, float) g
                                                          # burst read of 0x02–0x07, scale 64 LSB/g
al, ml = accel.read_version()                             # Read version, () → tuple(int, int) version codes
                                                          # (al_version, ml_version) from VERSION register
c1 = accel.read_customer(0)                               # Read scratch byte, (index) → int
                                                          # 0 → CUSTOMER_1, 1 → CUSTOMER_2
accel.write_customer(0, 0xA5)                             # Write scratch byte, (index, value) → None
                                                          # 0xA5 into CUSTOMER_1

st = accel.self_test()                                    # Run self-test, () → bool
                                                          # electrostatic self-test; reads STATUS.st_result
accel.soft_reset()                                        # Soft reset, () → None
                                                          # CTRL.soft_reset=1; 30 ms wait; range/bandwidth restored

accel.sleep()                                             # Enter sleep mode, () → None
accel.wake()                                              # Leave sleep mode, () → None
                                                          # 1.5 ms settle wait

print('raw={} temp={} status={} al={} ml={} c1={} st={}'.format(
    raw, temp, status, al, ml, c1, st))
