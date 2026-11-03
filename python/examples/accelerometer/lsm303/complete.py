"""Complete example for the LSM303 — exercise every method in the public API.

Constructs the Full driver, reads the acceleration and the magnetic field,
walks through every accelerometer configuration (range, data rate, operating
mode, axis enable, power-down), the high-pass filter, the FIFO, both
inertial interrupt generators, the click detector, the interrupt routing and
the magnetometer settings, and finally prints the compass heading.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.accelerometer.lsm303 import (
    LSM303Full, LSM303Source, I2C_ADDRESS_ACCEL, I2C_ADDRESS_MAG)

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
accel_connection = I2CConnection(i2c, I2C_ADDRESS_ACCEL)
mag_connection = I2CConnection(i2c, I2C_ADDRESS_MAG)
compass = LSM303Full(accel_connection, mag_connection)    # Create LSM303 Full driver, (accel_connection, mag_connection)
                                                         # verifies the magnetometer identity 0x483433

ax, ay, az = compass.read_acceleration()                  # Read acceleration, () → (x, y, z) float g
                                                         # burst-reads the six output registers
mx, my, mz = compass.read_magnetic()                      # Read magnetic field, () → (x, y, z) float µT
                                                         # burst-reads X, Z, Y and reorders them
print('accel {:.4f} {:.4f} {:.4f} g'.format(ax, ay, az))
print('mag {:.2f} {:.2f} {:.2f} uT'.format(mx, my, mz))

rx, ry, rz = compass.read_acceleration_raw()              # Read raw acceleration, () → (x, y, z) int counts
print('raw accel', rx, ry, rz)                            # signed counts at the current resolution

compass.set_range(4)                                      # Set full-scale range, (range_g 2|4|8|16) → None
                                                         # rewrites FS in CTRL_REG4_A and updates the sensitivity
compass.set_data_rate(50)                                 # Set output data rate, (rate_hz 0..5376) → None
                                                         # rewrites ODR in CTRL_REG1_A
compass.set_mode(LSM303Full.MODE_HIGH_RESOLUTION)         # Set operating mode, (mode 0|1|2) → None
                                                         # LPen/HR select low-power, normal or high-resolution
compass.set_axes(x=True, y=True, z=True)                  # Enable axes, (x=True, y=True, z=True) → None
                                                         # rewrites Xen, Yen and Zen
time.sleep(0.2)

compass.power_down()                                      # Power down the accelerometer, () → None
                                                         # ODR = 0000, the register contents are retained
compass.set_data_rate(100)                                # Set output data rate, (rate_hz 0..5376) → None
                                                         # leaves power-down and starts the conversions again

compass.set_high_pass(mode=0, cutoff=0, filter_output=False,
                      on_ia1=True, on_ia2=False, on_click=False)  # Configure high-pass filter, (mode 0..3, cutoff 0..3, filter_output=False, on_ia1=False, on_ia2=False, on_click=False) → None
                                                         # rewrites HPM, HPCF, FDS and the per-generator enables
print('reference', compass.reset_high_pass())             # Reset high-pass filter, () → int
                                                         # reads REFERENCE_A, which resets the filter
compass.set_reference(0)                                  # Set reference value, (value 0..255) → None
                                                         # the value the high-pass filter compares against

compass.set_fifo(LSM303Full.FIFO_STREAM, watermark=16)    # Configure FIFO, (mode 0..3, watermark=0, trigger_int2=False) → None
                                                         # stream mode with a 16-sample watermark
print('fifo level', compass.fifo_level())                  # Read FIFO level, () → int
                                                         # number of unread sample sets
print('fifo status', compass.read_fifo_status())          # Read FIFO status, () → (watermark, overrun, empty, level)
                                                         # decodes FIFO_SRC_REG_A
samples = compass.read_fifo()                             # Read FIFO, (count=None) → list of (x, y, z) float g
                                                         # pops one sample set per six-byte burst read
print('fifo samples', len(samples))

compass.set_motion_interrupt(1, 0x02, threshold_g=0.5,   # Configure motion interrupt, (generator 1|2, events 0..63, threshold_g, duration_ms=0, and_mode=False) → None
                           duration_ms=10, and_mode=False)  # XHIE event with a 0.5 g threshold
                                                         # writes INT1_CFG_A, INT1_THS_A and INT1_DURATION_A
compass.set_free_fall(generator=1, threshold_g=0.35, duration_ms=30)  # Arm free-fall, (generator=1, threshold_g=0.35, duration_ms=30) → None
                                                         # all three axes low, combined with AND
compass.set_orientation(generator=1, position=False, four_d=False)   # Configure orientation, (generator=1, position=False, four_d=False) → None
                                                         # 6D movement recognition on all three axes
compass.set_latch(1, True)                                # Latch the interrupt, (generator 1|2, enabled) → None
                                                         # the INT1 line stays asserted until the source is read
print('int1 source', compass.read_interrupt_source(1))    # Read interrupt source, (generator 1|2) → int
                                                         # reads INT1_SOURCE_A, which clears the latched flag

compass.set_click(single=0, double=0x04, threshold_g=0.8,  # Configure click detector, (single=0, double=0, threshold_g=0.5, time_limit_ms=10, latency_ms=20, window_ms=50) → None
                  time_limit_ms=20, latency_ms=40, window_ms=200)  # double click on the Z axis
                                                         # writes CLICK_CFG_A, CLICK_THS_A and the three time registers
print('click source', compass.read_click_source())         # Read click source, () → int
                                                         # reads CLICK_SRC_A, which clears the latched flag

compass.set_interrupt_polarity(False)                     # Set interrupt polarity, (active_low) → None
                                                         # H_LACTIVE in CTRL_REG6_A; False keeps the active-high default
compass.enable_interrupt(LSM303Source.SOURCE_CLICK, 1)    # Route an interrupt, (source, pin=1) → None
                                                         # only the routing is set up, the generator is configured separately
compass.disable_interrupt(LSM303Source.SOURCE_CLICK, 1)   # Remove an interrupt, (source, pin=1) → None
                                                         # clears the routing bit again

compass.set_mag_range(250)                                # Set magnetometer range, (range_ut 130..810) → None
                                                         # rewrites GN in CRB_REG_M and updates the gains
compass.set_mag_data_rate(30)                             # Set magnetometer data rate, (rate_hz 0.75..220) → None
                                                         # rewrites DO in CRA_REG_M, keeping TEMP_EN
compass.set_mag_mode(LSM303Full.MAG_MODE_CONTINUOUS)       # Set magnetometer mode, (mode 0|1|2) → None
                                                         # continuous, single or sleep conversion
rx, ry, rz = compass.read_magnetic_raw()                  # Read raw magnetic field, () → (x, y, z) int counts
print('raw mag', rx, ry, rz)                               # signed counts in X, Y, Z order
print('mag status', compass.read_mag_status())             # Read magnetometer status, () → (drdy, lock)
                                                         # decodes SR_REG_M
print('identity', compass.read_mag_identity())             # Read magnetometer identity, () → bytes
                                                         # returns the three identity bytes
compass.enable_temperature(True)                          # Enable the temperature sensor, (enabled) → None
                                                         # sets TEMP_EN in CRA_REG_M
print('temperature {:.2f} C'.format(compass.read_temperature_delta()))  # Read relative temperature, () → float °C
                                                         # 8 LSB/°C, relative to an unspecified reference

print('heading {:.1f} deg'.format(compass.heading()))     # Read the compass heading, (tilt_compensate=True) → float °
                                                         # tilt-compensated with the accelerometer
print('flat heading {:.1f} deg'.format(compass.heading(False)))  # Read the compass heading, (tilt_compensate=True) → float °
                                                         # the magnetic-only heading

compass.reboot()                                          # Reboot the accelerometer, () → None
                                                         # reloads the factory trim values and re-runs init()
