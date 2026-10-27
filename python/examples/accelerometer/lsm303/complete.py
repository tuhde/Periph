"""Complete example for the LSM303 — exercise every method in the public API.

Constructs the Full driver, reconfigures the accelerometer (range, data rate,
mode, axes) and the magnetometer (gain, rate, mode), enables the relative
temperature sensor, configures the high-pass filter and FIFO, arms the
inertial generators and click detection, routes interrupt sources to INT1 /
INT2, reads all raw / status registers, and computes the compass heading.
"""

from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.accelerometer.lsm303 import LSM303Full

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
accel_conn = I2CConnection(i2c, 0x19)
mag_conn = I2CConnection(i2c, 0x1E)
compass = LSM303Full(accel_conn, mag_conn)              # Create LSM303 Full driver, (accel_connection, mag_connection)

ident = compass.read_mag_identity()                     # Read magnetometer identity, () → bytes
                                                         # IRA/IRB/IRC = 0x48 0x34 0x33 ("H43", same as HMC5883L)
compass.set_range(4)                                    # Set accelerometer range, (range_g) → None g
                                                         # selects ±4 g and updates the cached sensitivity
compass.set_data_rate(50)                               # Set accelerometer data rate, (rate_hz) → None Hz
                                                         # 50 Hz ODR; 1620 / 5376 Hz need low-power mode
compass.set_mode(2)                                     # Set operating mode, (mode) → None
                                                         # 2 = high-resolution (12-bit, 1 mg/LSB at ±2 g)
compass.set_axes(True, True, True)                      # Set axes enabled, (x=True, y=True, z=True) → None
                                                         # Xen / Yen / Zen bits in CTRL_REG1_A
compass.set_mag_range(130)                              # Set magnetometer range, (range_ut) → None µT
                                                         # writes CRB_REG_M gain ±1.3 gauss and updates the cached gains
compass.set_mag_data_rate(30)                           # Set magnetometer data rate, (rate_hz) → None Hz
                                                         # DO bits in CRA_REG_M; keeps TEMP_EN
compass.set_mag_mode(0)                                 # Set magnetometer mode, (mode) → None
                                                         # 0 = continuous conversion
compass.enable_temperature(True)                        # Enable temperature sensor, (enabled) → None
                                                         # sets TEMP_EN in CRA_REG_M

temp = compass.read_temperature_delta()                 # Read relative temperature, () → float °C
                                                         # 12-bit left-justified, 8 LSB/°C, relative only (no absolute reference)
compass.set_high_pass(mode=0, cutoff=0)                 # Configure high-pass filter, (mode=0, cutoff=0, filter_output=False, on_ia1=False, on_ia2=False, on_click=False) → None
                                                         # HPM=00 normal mode, filter bypassed from output and interrupts
ref = compass.reset_high_pass()                         # Reset high-pass filter, () → int
                                                         # reads REFERENCE_A, which resets the filter in HPM=00
compass.set_reference(0)                                # Set high-pass reference, (value) → None
                                                         # REFERENCE_A for the reference-signal mode HPM=01

compass.set_fifo(2, 8)                                  # Configure FIFO, (mode, watermark=0, trigger_int2=False) → None
                                                         # stream mode, 8-sample watermark, FIFO_EN set in CTRL_REG5_A
level = compass.fifo_level()                            # FIFO unread sample sets, () → int
                                                         # FSS<4:0> from FIFO_SRC_REG_A (0–32)
wm, ov, empty, level = compass.read_fifo_status()       # Read FIFO status, () → tuple(bool, bool, bool, int)
                                                         # watermark / overrun / empty flags plus the sample count
samples = compass.read_fifo()                           # Drain the FIFO, (count=None) → list[tuple(float, float, float)] g
                                                         # pops one 6-byte burst per sample set, converted to *g*
compass.set_fifo(0)                                     # Configure FIFO, (mode, watermark=0, trigger_int2=False) → None
                                                         # back to bypass — only bypass empties the buffer

compass.set_free_fall(1, 0.35, 60)                      # Configure free-fall, (generator=1, threshold_g=0.35 g, duration_ms=30 ms) → None
                                                         # INT1_CFG_A = 0x95: all three axes low, AND mode
compass.set_latch(1, True)                              # Set interrupt latching, (generator, enabled) → None
                                                         # LIR_INT1: INT output stays asserted until INT1_SOURCE_A is read
compass.enable_interrupt(LSM303Full.SOURCE_IA1, 1)      # Route interrupt source, (source, pin=1) → None
                                                         # generator 1 events → INT1
compass.set_click(double=0x04, threshold_g=0.8, time_limit_ms=20, latency_ms=40, window_ms=200)
                                                         # Configure click detection, (single=0, double=0, threshold_g=0.5 g, time_limit_ms=10 ms, latency_ms=20 ms, window_ms=50 ms) → None
compass.enable_interrupt(LSM303Full.SOURCE_CLICK, 2)    # Route interrupt source, (source, pin=1) → None
                                                         # click events → INT2
compass.set_motion_interrupt(2, 0x15, 0.5, 100, and_mode=True)
                                                         # Configure motion interrupt, (generator, events, threshold_g, duration_ms=0, and_mode=False) → None
compass.enable_interrupt(LSM303Full.SOURCE_IA2, 2)      # Route interrupt source, (source, pin=1) → None
                                                         # generator 2 events → INT2
compass.set_orientation(2, position=True)               # Configure orientation recognition, (generator=1, position=False, four_d=False) → None
                                                         # 6D position recognition on generator 2
compass.disable_interrupt(LSM303Full.SOURCE_IA2, 2)     # Remove interrupt source, (source, pin=1) → None
                                                         # unroutes generator 2 from INT2 again
compass.set_interrupt_polarity(False)                   # Set INT polarity, (active_low) → None
                                                         # H_LACTIVE = 0: INT1 / INT2 active-high

raw = compass.read_acceleration_raw()                   # Read raw acceleration, () → tuple(int, int, int) counts
                                                         # signed counts at the current resolution (12-bit in high-resolution mode)
x, y, z = compass.read_acceleration()                   # Read 3-axis acceleration, () → tuple(float, float, float) g
                                                         # burst-reads 0x28–0x2D with auto-increment, 1 mg/LSB at ±2 g
mx, my, mz = compass.read_magnetic()                    # Read 3-axis magnetic field, () → tuple(float, float, float) µT
                                                         # on-wire order X, Z, Y high-byte-first, reordered to X, Y, Z
mrx, mry, mrz = compass.read_magnetic_raw()             # Read raw magnetic field, () → tuple(int, int, int) counts
                                                         # signed 12-bit counts in X, Y, Z order
avail = compass.new_data_available()                    # Check new accelerometer data, () → bool
                                                         # STATUS_REG_A.ZYXDA
status = compass.read_status()                          # Read accelerometer status, () → int
                                                         # STATUS_REG_A byte (overrun + data-ready flags)
mdrdy = compass.mag_data_ready()                        # Check new magnetometer data, () → bool
                                                         # SR_REG_M.DRDY
drdy, lock = compass.read_mag_status()                  # Read magnetometer status, () → tuple(bool, bool)
                                                         # DRDY flag and output-register LOCK
src1 = compass.read_interrupt_source(1)                 # Read interrupt source register, (generator) → int
                                                         # INT1_SOURCE_A byte; reading clears the latched interrupt
click = compass.read_click_source()                     # Read click source register, () → int
                                                         # CLICK_SRC_A byte; reading clears the click interrupt
status = compass.poll_interrupt()                       # Poll all enabled interrupt sources, () → int
                                                         # combined SOURCE_* bitmask; reading clears the latched flags
hdg = compass.heading()                                 # Compute compass heading, (tilt_compensate=True) → float °
                                                         # tilt-compensated atan2 heading from both sensors, 0–360°

compass.on_interrupt(lambda status: None, 1)            # Subscribe to INT line, (callback, pin=1) → None
                                                         # uses the connection's INT pin, or a 5 ms polling thread as fallback
compass.off_interrupt(1)                                # Unsubscribe from INT line, (pin=1) → None
                                                         # removes the callback and stops edge delivery / polling

compass.power_down()                                    # Enter power-down, () → None
                                                         # ODR = 0000; register contents are retained
compass.set_data_rate(50)                               # Set accelerometer data rate, (rate_hz) → None Hz
                                                         # leaving power-down again; first sample after the turn-on time
compass.reboot()                                        # Reboot memory content, () → None
                                                         # BOOT = 1 reloads the factory trim; re-run init afterwards
