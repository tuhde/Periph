"""Complete example for the LSM303DLHC — exercise every method in the Full API.

Constructs the Full driver, configures both sensors, drains the FIFO,
arms inertial, free-fall, 6D and click interrupts, routes them to INT1,
polls and subscribes to interrupts, reads the magnetometer and
temperature, and finally powers the accelerometer down. Reboot is last
because it is a destructive reset of the accelerometer.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.accelerometer.lsm303 import LSM303Full, LSM303Source

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
accel_conn = I2CConnection(i2c, 0x19)                   # Create accelerometer connection, (bus, addr=0x19) → None
mag_conn = I2CConnection(i2c, 0x1E)                     # Create magnetometer connection, (bus, addr=0x1E) → None
compass = LSM303Full(accel_conn, mag_conn)              # Create LSM303 Full driver, (accel_connection, mag_connection) → None


def on_int(status):
    print('interrupt status=0x{:02X}'.format(status))


compass.set_range(4)                                    # Set accelerometer range, (range_g=2) → None g
                                                         # selects ±4 g; sensitivity becomes 2 mg/LSB in high-resolution mode
compass.set_mode(2)                                     # Set accelerometer mode, (mode=2) → None
                                                         # high-resolution (12-bit) mode, LPen = 0, HR = 1
compass.set_data_rate(50)                               # Set accelerometer data rate, (rate_hz=100) → None Hz
                                                         # 50 Hz output data rate; valid in high-resolution mode
compass.set_axes(x=True, y=True, z=True)                # Enable accelerometer axes, (x=True, y=True, z=True) → None
                                                         # all three axes on; clears nothing else in CTRL_REG1_A
compass.set_high_pass(mode=0, cutoff=1, on_ia1=True)    # Configure high-pass filter, (mode=0, cutoff=0, …) → None
                                                         # normal mode, cutoff code 1, applied to generator 1
ref = compass.reset_high_pass()                         # Reset high-pass filter, () → int
                                                         # reading REFERENCE_A resets the filter in normal mode
compass.set_reference(0)                                # Set high-pass reference, (value) → None
                                                         # REFERENCE_A = 0 for reference-filter mode (HPM = 01)

compass.set_fifo(2, watermark=16)                       # Configure FIFO, (mode, watermark=0, trigger_int2=False) → None
                                                         # stream mode, 16-sample watermark, trigger source INT1
time.sleep(0.2)
level = compass.fifo_level()                            # Read FIFO level, () → int
                                                         # number of unread sample sets (FSS)
samples = compass.read_fifo(4)                          # Read FIFO samples, (count=None) → list[tuple(float, float, float)] g
                                                         # one 6-byte burst per sample set, up to 4 sets
wm, overrun, empty, fifo_level = compass.read_fifo_status()  # Read FIFO status, () → tuple(bool, bool, bool, int)
                                                         # decodes FIFO_SRC_REG_A
compass.set_fifo(0)                                     # Configure FIFO, (mode=0) → None
                                                         # bypass mode; returning to bypass clears the buffer

compass.set_motion_interrupt(1, 0x3F, threshold_g=1.0, duration_ms=50)  # Configure motion interrupt, (generator, events, threshold_g, duration_ms=0, and_mode=False) → None g, ms
                                                         # OR of all six axis events, 1.0 g threshold, 50 ms duration
compass.set_free_fall(generator=2, threshold_g=0.35, duration_ms=30)    # Configure free-fall, (generator=1, threshold_g=0.35, duration_ms=30) → None g, ms
                                                         # all axes low, AND combination, on generator 2
compass.set_orientation(1, position=False, four_d=True)  # Configure 6D / 4D orientation, (generator=1, position=False, four_d=False) → None
                                                         # 4D movement recognition on generator 1
compass.set_latch(1, True)                              # Latch generator 1, (generator, enabled) → None
                                                         # interrupt stays asserted until INT1_SOURCE_A is read
src = compass.read_interrupt_source(1)                  # Read interrupt source, (generator) → int
                                                         # INT1_SOURCE_A byte; reading clears the latched flag

compass.set_click(single=4, double=0, threshold_g=0.5, time_limit_ms=10, latency_ms=20, window_ms=50)  # Configure click, (single=0, double=0, threshold_g=0.5, time_limit_ms=10, latency_ms=20, window_ms=50) → None g, ms
                                                         # single click on Z (mask 4), 0.5 g threshold, timing in ms
click = compass.read_click_source()                     # Read click source, () → int
                                                         # CLICK_SRC_A byte; reading clears the click flags

compass.set_interrupt_polarity(False)                   # Set interrupt polarity, (active_low) → None
                                                         # INT1 / INT2 active high (H_LACTIVE = 0)
compass.enable_interrupt(LSM303Source.SOURCE_CLICK, 1)  # Route interrupt source, (source, pin=1) → None
                                                         # click on INT1
compass.enable_interrupt(LSM303Source.SOURCE_IA1, 1)    # Route interrupt source, (source, pin=1) → None
                                                         # generator 1 event on INT1
compass.disable_interrupt(LSM303Source.SOURCE_IA1, 1)   # Unroute interrupt source, (source, pin=1) → None
                                                         # removes generator 1 from INT1 again

compass.on_interrupt(on_int, 1)                         # Subscribe to interrupt, (callback, pin=1) → None
                                                         # callback receives the combined status int
time.sleep(0.2)
status = compass.poll_interrupt()                       # Poll interrupt status, () → int
                                                         # reads every routed source; INTx_SOURCE_A and CLICK_SRC_A are cleared
compass.off_interrupt(1)                                # Unsubscribe from interrupt, (pin=1) → None
                                                         # stops the edge handler or polling thread

compass.set_mag_range(130)                              # Set magnetometer range, (range_ut) → None µT
                                                         # ±130 µT (±1.3 gauss), the default gain
compass.set_mag_data_rate(15)                           # Set magnetometer data rate, (rate_hz) → None Hz
                                                         # 15 Hz output rate
compass.set_mag_mode(0)                                 # Set magnetometer mode, (mode) → None
                                                         # continuous conversion
time.sleep(0.2)
mraw = compass.read_magnetic_raw()                      # Read magnetic field raw, () → tuple(int, int, int) counts
                                                         # signed counts in X, Y, Z order
mag_ready = compass.mag_data_ready()                    # Check magnetometer data ready, () → bool
                                                         # SR_REG_M.DRDY
mag_drdy, mag_lock = compass.read_mag_status()          # Read magnetometer status, () → tuple(bool, bool)
                                                         # decodes SR_REG_M
ident = compass.read_mag_identity()                     # Read magnetometer identity, () → bytes
                                                         # IRA/IRB/IRC, expect b'H43'
mx, my, mz = compass.read_magnetic()                    # Read magnetic field, () → tuple(float, float, float) µT
                                                         # converts raw counts to µT, reordered to X, Y, Z

compass.enable_temperature(True)                        # Enable temperature sensor, (enabled) → None
                                                         # sets TEMP_EN in CRA_REG_M
temp = compass.read_temperature_delta()                 # Read relative temperature, () → float °C
                                                         # 8 LSB/°C, relative to an unspecified reference
heading = compass.heading(tilt_compensate=True)         # Read compass heading, (tilt_compensate=True) → float °
                                                         # tilt-compensated heading, 0–360 degrees

ax, ay, az = compass.read_acceleration()                # Read acceleration, () → tuple(float, float, float) g
                                                         # burst read, converted at the current range and mode
ax_c, ay_c, az_c = compass.read_acceleration_raw()      # Read acceleration raw, () → tuple(int, int, int) counts
                                                         # signed counts at the current resolution
new = compass.new_data_available()                      # Check accelerometer data ready, () → bool
                                                         # STATUS_REG_A.ZYXDA
stat = compass.read_status()                            # Read accelerometer status, () → int
                                                         # STATUS_REG_A byte

compass.set_axes(x=True, y=True, z=True)                # Enable accelerometer axes, (x=True, y=True, z=True) → None
compass.power_down()                                    # Power down accelerometer, () → None
                                                         # ODR = 0000; register contents are retained
compass.set_data_rate(100)                              # Set accelerometer data rate, (rate_hz=100) → None Hz
                                                         # wakes the accelerometer back up at 100 Hz

compass.reboot()                                        # Reboot accelerometer memory, () → None
                                                         # BOOT = 1, waits 5 ms; construct a new driver afterwards
