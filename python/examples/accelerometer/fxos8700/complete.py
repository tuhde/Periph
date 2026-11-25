"""Complete example for the FXOS8700 — exercise every method in the public API.

Constructs the Full driver and walks through the measurements, the operating
mode, range, data rate and oversampling settings, the offsets, the
magnetometer calibration helpers, the FIFO, every detector, Auto-SLEEP, the
interrupt routing and the raw register escape hatch.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.accelerometer.fxos8700 import FXOS8700Full, FXOS8700Source, I2C_ADDRESS

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
compass = FXOS8700Full(connection)                        # Create FXOS8700 Full driver, (connection)
                                                         # verifies WHO_AM_I, resets and starts hybrid mode

ax, ay, az = compass.read_accel()                         # Read acceleration, () → (x, y, z) float g
                                                         # burst-reads 0x01-0x06, 14-bit left-justified
mx, my, mz = compass.read_mag()                           # Read magnetic field, () → (x, y, z) float µT
                                                         # burst-reads 0x33-0x38, 0.1 µT/LSB
print('accel {:.4f} {:.4f} {:.4f} g'.format(ax, ay, az))
print('mag {:.2f} {:.2f} {:.2f} uT'.format(mx, my, mz))

accel, mag = compass.read_hybrid()                        # Read both sensors, () → ((x, y, z) g, (x, y, z) µT)
                                                         # one 12-byte burst from 0x01 (hyb_autoinc_mode)
print('hybrid', accel, mag)
print('raw accel', compass.read_raw_accel())              # Read raw accelerometer, () → (x, y, z) int counts
                                                         # signed 14-bit counts
print('raw mag', compass.read_raw_mag())                  # Read raw magnetometer, () → (x, y, z) int counts
                                                         # signed 16-bit counts, 0.1 µT/LSB
print('temperature', compass.read_temperature())          # Read die temperature, () → float °C
                                                         # int8(TEMP) × 0.96, relative only
print('new accel data', compass.new_accel_data())         # Check accelerometer data ready, () → bool
                                                         # STATUS.zyxdr
print('new mag data', compass.new_mag_data())             # Check magnetometer data ready, () → bool
                                                         # M_DR_STATUS.zyxdr
print('status', compass.read_status())                    # Read STATUS, () → int
print('mag status', compass.read_mag_status())            # Read M_DR_STATUS, () → int
print('system mode', compass.read_system_mode())          # Read SYSMOD, () → int 0 standby | 1 wake | 2 sleep

compass.set_mode(FXOS8700Full.MODE_HYBRID)                # Select operating mode, (mode 0|1|2) → None
                                                         # 0 accel only, 1 mag only, 2 hybrid
compass.set_range(4)                                      # Set accelerometer range, (range_g 2|4|8) → None
                                                         # rewrites XYZ_DATA_CFG.fs and the cached sensitivity
compass.set_data_rate(200)                                # Set system data rate, (rate_hz 800..1.5625) → None
                                                         # each sensor runs at half this rate in hybrid mode
compass.set_accel_oversampling(0, sleep_mode=0)           # Set accelerometer oversampling, (mode 0..3, sleep_mode=None) → None
                                                         # MODS / SMODS in CTRL_REG2
compass.set_mag_oversampling(7, sleep_ratio=7)            # Set magnetometer oversampling, (ratio 0..7, sleep_ratio=None) → None
                                                         # m_os / m_aslp_os
compass.set_low_noise(False)                              # Set low-noise mode, (enabled bool) → None
                                                         # lnoise limits the range to ±4 g
compass.set_resolution(14)                                # Set accelerometer resolution, (bits 14|8) → None
                                                         # 8 selects fast read (MSB only)
compass.set_high_pass_output(False, cutoff=0)             # Set high-pass output, (enabled bool, cutoff 0..3) → None
                                                         # hpf_out and sel
compass.standby()                                         # Enter STANDBY, () → None
                                                         # polls SYSMOD until 0 (up to 300 ms)
compass.activate()                                        # Enter ACTIVE, () → None
                                                         # waits the 2/ODR + 2 ms turn-on time

compass.set_accel_offset(0.0, 0.0, 0.0)                   # Set accelerometer offsets, (x_g, y_g, z_g) → None
                                                         # OFF_X/Y/Z, 2 mg/LSB
print('accel offset', compass.get_accel_offset())         # Get accelerometer offsets, () → (x, y, z) float g
compass.set_mag_offset(0.0, 0.0, 0.0)                     # Set hard-iron offsets, (x_ut, y_ut, z_ut) → None
                                                         # 15-bit values subtracted from the output
print('mag offset', compass.get_mag_offset())             # Get hard-iron offsets, () → (x, y, z) float µT
compass.set_mag_raw(False)                                # Select raw magnetometer output, (raw bool) → None
                                                         # False applies the hard-iron offsets
compass.set_mag_auto_calibration(True)                    # Hardware hard-iron calibration, (enabled bool) → None
                                                         # MAX/MIN midpoint → M_OFF_* every ODR cycle
time.sleep(1)
print('min/max', compass.read_mag_min_max())              # Read tracked min / max, () → ((x, y, z), (x, y, z)) float µT
compass.set_mag_auto_calibration(False)
compass.reset_mag_min_max()                               # Reset min / max tracker, () → None
compass.set_mag_reset(0)                                  # Set automatic magnetic reset, (mode 0..3) → None
                                                         # 0 every ODR cycle, 3 disabled
compass.trigger_mag_reset()                               # Trigger one-shot degauss, () → None
print('one-shot', compass.measure_mag_once())             # Measure once in STANDBY, () → (x, y, z) float µT
                                                         # m_ost, restores ACTIVE
print('heading', compass.heading(declination_deg=2.5))    # Compute tilt-compensated heading, (declination_deg=0.0) → float degrees
                                                         # software compensation from one accel + one mag sample

compass.set_fifo(FXOS8700Full.FIFO_CIRCULAR, watermark=16)  # Configure FIFO, (mode 0..3, watermark=0) → None
compass.set_fifo_trigger(motion=True)                     # Select FIFO trigger sources, (transient, orientation, pulse, motion) → None
compass.set_fifo_gate(False)                              # Set FIFO gate, (enabled bool) → None
time.sleep(0.2)
print('fifo level', compass.fifo_level())                 # Read FIFO level, () → int
print('fifo status', compass.read_fifo_status())          # Read FIFO status, () → (overflow, watermark, count)
print('fifo', compass.read_fifo(4))                       # Drain FIFO, (count=None) → [(x, y, z) float g]
compass.set_fifo(FXOS8700Full.FIFO_DISABLED)

compass.set_freefall_motion(True, threshold_g=0.3, debounce_ms=60, latch=True)  # Configure freefall / motion, (motion bool, axes=7, threshold_g=0.3, debounce_ms=0, latch=False, debounce_clear=False) → None
print('motion', compass.read_motion_source())             # Read A_FFMT_SRC, () → int
compass.set_transient(threshold_g=0.4, debounce_ms=20)    # Configure transient detector, (axes=7, threshold_g=0.3, debounce_ms=0, bypass_hpf=False, latch=False) → None
print('transient', compass.read_transient_source())       # Read TRANSIENT_SRC, () → int
compass.set_pulse(single=0x04, double=0x00)               # Configure tap detection, (single=0, double=0, threshold_g=(0.5,)*3, time_limit_ms=30, latency_ms=100, window_ms=300, latch=False, double_abort=False, lpf=True, hpf=True) → None
print('pulse', compass.read_pulse_source())               # Read PULSE_SRC, () → int
compass.set_orientation(enabled=True, debounce_ms=100)    # Configure orientation detection, (enabled=True, debounce_ms=0, threshold_deg=45, hysteresis=4, z_lock=4, back_front=2) → None
print('orientation', compass.read_orientation())          # Read orientation, () → (changed, lockout, portrait_landscape, back)
compass.set_auto_sleep(True, sleep_rate_hz=12.5, timeout_s=10,
                       wake_sources=FXOS8700Source.SOURCE_FF_MT)  # Configure Auto-SLEEP, (enabled bool, sleep_rate_hz=50, timeout_s=10, wake_sources=0) → None

compass.set_interrupt_pins(active_high=False, open_drain=False)  # Configure INT pins, (active_high=False, open_drain=False) → None
compass.enable_interrupt(FXOS8700Source.SOURCE_FF_MT | FXOS8700Source.SOURCE_ORIENTATION, pin=1)  # Enable and route interrupt, (source mask, pin 1|2) → None
print('interrupts', compass.poll_interrupt())             # Poll and clear interrupt sources, () → int
compass.on_interrupt(lambda status: print('INT', status), pin=1)  # Subscribe to INT, (callback, pin=1) → None
time.sleep(1)
compass.off_interrupt(pin=1)                              # Unsubscribe from INT, (pin=1) → None
compass.disable_interrupt(FXOS8700Source.SOURCE_FF_MT | FXOS8700Source.SOURCE_ORIENTATION)  # Disable interrupt sources, (source mask) → None

print('A_VECM_CFG', compass.read_register(0x5F, 1))       # Read raw registers, (reg, length=1) → bytes
compass.write_register(0x5F, bytes([0x00]))               # Write raw registers, (reg, data bytes) → None
ok, dx, dy, dz = compass.self_test()                      # Run accelerometer self-test, () → (ok bool, dx, dy, dz int counts)
                                                         # electrostatic test, checks X ≥ 192, Y ≥ 270, Z ≥ 1275
print('self-test', ok, dx, dy, dz)
compass.reset()                                           # Software reset, () → None
                                                         # the NACK on the write is tolerated; re-run init() afterwards
compass.init()                                            # Re-initialise, () → None
