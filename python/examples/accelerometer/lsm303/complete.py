"""Complete example for the LSM303 — exercise every method in the public API.

Configures the accelerometer range / data rate / mode / axes, reads raw and
status data, uses the high-pass filter, the FIFO, both interrupt generators,
orientation, click detection, interrupt routing and delivery, sets every
magnetometer option, reads temperature and heading, and reboots.
"""

from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.accelerometer.lsm303 import LSM303Full, LSM303Source

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
accel_connection = I2CConnection(i2c, 0x19)
mag_connection = I2CConnection(i2c, 0x1E)
compass = LSM303Full(accel_connection, mag_connection)      # Create LSM303 Full driver, (accel_connection, mag_connection)

# --- Accelerometer range, mode, data rate, axes ---
compass.set_range(8)                                        # Set accelerometer range, (range_g=2) → None g
                                                            # FS=10 in CTRL_REG4_A; sensitivity becomes 4 mg/LSB
compass.set_mode(1)                                         # Set accelerometer mode, (mode=2) → None
                                                            # 12-bit high-resolution → 10-bit normal
compass.set_data_rate(400)                                  # Set accelerometer data rate, (rate_hz=100) → None Hz
                                                            # ODR=0111; waits one ODR period
compass.set_axes(True, True, True)                          # Enable accelerometer axes, (x=True, y=True, z=True) → None
                                                            # Xen/Yen/Zen in CTRL_REG1_A

# --- Power cycle and restore defaults ---
compass.power_down()                                        # Stop accelerometer conversions, () → None
                                                            # ODR=0000; registers retained
compass.set_data_rate(100)                                  # Set accelerometer data rate, (rate_hz=0) → None Hz
                                                            # leave power-down again
compass.set_mode(2)                                         # Set accelerometer mode, (mode=1) → None
                                                            # back to high-resolution 12-bit
compass.set_range(2)                                        # Set accelerometer range, (range_g=8) → None g
                                                            # back to ±2 g (1 mg/LSB)

# --- Reads ---
ax, ay, az = compass.read_acceleration()                    # Read 3-axis acceleration, () → tuple(float, float, float) g
                                                            # 6-byte burst of 0x28–0x2D, scaled to g
rx, ry, rz = compass.read_acceleration_raw()                # Read raw acceleration counts, () → tuple(int, int, int) LSB
                                                            # signed counts at the current resolution
ready = compass.new_data_available()                        # Check new acceleration data, () → bool
                                                            # STATUS_REG_A.ZYXDA
status = compass.read_status()                              # Read accelerometer status, () → int
                                                            # overrun and data-available flags

# --- High-pass filter ---
compass.set_high_pass(mode=1, cutoff=2, filter_output=True,
                      on_ia1=True, on_ia2=False,
                      on_click=True)                        # Configure high-pass filter, (mode=0, cutoff=0, filter_output=False, on_ia1=False, on_ia2=False, on_click=False) → None
                                                            # HPM=01, HPCF=10, filtered data routed to output, IA1 and click
ref = compass.reset_high_pass()                             # Reset high-pass filter, () → int
                                                            # reading REFERENCE_A resets the filter in HPM=00
compass.set_reference(0x40)                                 # Set high-pass reference, (value=0) → None
                                                            # REFERENCE_A = 0x40 for HPM=01

# --- FIFO ---
compass.set_fifo(1, watermark=16)                           # Configure FIFO, (mode=0, watermark=0, trigger_int2=False) → None
                                                            # FIFO mode with a 16-sample watermark; FIFO_EN set
level = compass.fifo_level()                                # Read FIFO level, () → int
                                                            # FSS<4:0> unread sample sets
watermark, overrun, empty, lvl = compass.read_fifo_status() # Read FIFO status, () → tuple(bool, bool, bool, int)
                                                            # WTM / OVRN_FIFO / EMPTY plus the level
samples = compass.read_fifo()                               # Drain FIFO, (count=None) → list(tuple(float, float, float)) g
                                                            # one 6-byte burst per sample set
compass.set_fifo(0)                                         # Configure FIFO, (mode=1) → None
                                                            # back to bypass, which empties the buffer

# --- Inertial interrupt generators ---
compass.set_motion_interrupt(1, 0x3F, 0.5, 30, True)        # Configure motion interrupt, (generator=1, events=0, threshold_g=0, duration_ms=0, and_mode=False) → None
                                                            # generator 1, all axis events, AND-combined
compass.set_free_fall(generator=1, threshold_g=0.35,
                      duration_ms=30)                       # Configure free-fall, (generator=1, threshold_g=0.35, duration_ms=30) → None
                                                            # INT1_CFG_A = 0x95: all axes low, AND mode
compass.set_orientation(generator=1, position=False,
                        four_d=False)                       # Configure orientation, (generator=1, position=False, four_d=False) → None
                                                            # 6D movement recognition
compass.set_latch(1, True)                                  # Latch generator 1, (generator=1, enabled=True) → None
                                                            # stays high until INT1_SOURCE_A is read
src1 = compass.read_interrupt_source(1)                     # Read interrupt source, (generator=1) → int
                                                            # reading it clears the interrupt
compass.set_latch(1, False)                                 # Latch generator 1, (generator=1, enabled=True) → None
                                                            # back to self-resetting

# --- Click detection ---
compass.set_click(single=0x01, double=0x04, threshold_g=0.5,
                  time_limit_ms=10, latency_ms=20,
                  window_ms=50)                             # Configure click detection, (single=0, double=0, threshold_g=0.5, time_limit_ms=10, latency_ms=20, window_ms=50) → None
                                                            # single click on X, double click on Z
click = compass.read_click_source()                         # Read click source, () → int
                                                            # CLICK_SRC_A; reading it clears the interrupt

# --- Interrupt routing and delivery ---
compass.set_interrupt_polarity(False)                       # Set interrupt polarity, (active_low=False) → None
                                                            # INT1 and INT2 stay active-high
compass.enable_interrupt(LSM303Source.SOURCE_DATA_READY, 1) # Enable interrupt source, (source, pin=1) → None
                                                            # routes new-data to INT1
compass.enable_interrupt(LSM303Source.SOURCE_CLICK, 1)      # Enable interrupt source, (source, pin=1) → None
                                                            # routes click to INT1 too
compass.disable_interrupt(LSM303Source.SOURCE_DATA_READY, 1) # Disable interrupt source, (source, pin=1) → None
                                                            # removes new-data from INT1


def on_int(status):
    """Print the combined interrupt status."""
    print('interrupt status: 0x{:04X}'.format(status))


compass.on_interrupt(on_int, 1)                             # Subscribe to INT1, (callback, pin=1) → None
                                                            # callback receives the combined status int
compass.off_interrupt(1)                                    # Unsubscribe from INT1, (pin=1) → None
                                                            # removes this chip's handler
status = compass.poll_interrupt()                           # Poll interrupt status, () → int
                                                            # reads every enabled source register

# --- Magnetometer ---
compass.set_mag_range(470)                                  # Set magnetometer range, (range_ut=130) → None µT
                                                            # GN=101, ±4.7 gauss
compass.set_mag_data_rate(30)                               # Set magnetometer data rate, (rate_hz=15) → None Hz
                                                            # DO=101, keeps TEMP_EN
compass.set_mag_mode(1)                                     # Set magnetometer mode, (mode=0) → None
                                                            # single conversion, then sleep
compass.set_mag_mode(0)                                     # Set magnetometer mode, (mode=1) → None
                                                            # back to continuous conversion
mrx, mry, mrz = compass.read_magnetic_raw()                 # Read raw magnetic counts, () → tuple(int, int, int) LSB
                                                            # X, Y, Z order after reordering
mx, my, mz = compass.read_magnetic()                        # Read 3-axis magnetic field, () → tuple(float, float, float) µT
                                                            # burst of 0x03–0x08, reordered from X, Z, Y
mready = compass.mag_data_ready()                           # Check new magnetometer data, () → bool
                                                            # SR_REG_M.DRDY
drdy, lock = compass.read_mag_status()                      # Read magnetometer status, () → tuple(bool, bool)
                                                            # DRDY and LOCK flags
identity = compass.read_mag_identity()                      # Read magnetometer identity, () → bytes
                                                            # IRA/IRB/IRC = 0x48 0x34 0x33
compass.enable_temperature(True)                            # Enable temperature sensor, (enabled=True) → None
                                                            # sets TEMP_EN in CRA_REG_M
temp = compass.read_temperature_delta()                     # Read relative temperature, () → float °C
                                                            # 8 LSB/°C, no absolute reference
heading = compass.heading(tilt_compensate=True)             # Compute compass heading, (tilt_compensate=True) → float °
                                                            # tilt-compensated using both sensors
flat = compass.heading(tilt_compensate=False)               # Compute compass heading, (tilt_compensate=False) → float °
                                                            # flat compass, atan2(my, mx)

# --- System ---
compass.reboot()                                            # Reboot accelerometer memory, () → None
                                                            # BOOT=1, then the Initialization Sequence runs again
