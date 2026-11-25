"""Complete example for the MMA8451Q — exercise every method in the public API.

Configures the range, data rate, oversampling mode, resolution, high-pass
output and offsets; arms the freefall / motion, transient, tap and
orientation detectors; exercises the FIFO in circular mode; enables the
interrupt sources; and runs the self-test and software reset.
"""

from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.accelerometer.mma8451q import MMA8451QFull

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, 0x1C)
accel = MMA8451QFull(connection)                             # Create MMA8451Q Full driver, (connection)

accel.set_range(4)                                          # Set measurement range, (range_g) → None g
                                                           # selects ±4 g; sensitivity drops from 4096 to 2048 counts/g
accel.set_data_rate(100)                                    # Set output data rate, (rate_hz) → None Hz
                                                           # 100 Hz (DR bits 011); also updates the debounce / pulse time steps
accel.set_oversampling(2)                                   # Set oversampling mode, (mode, sleep_mode=None) → None
                                                           # 2 = high resolution; halves the debounce step to 2.5 ms
accel.set_low_noise(False)                                  # Set low-noise mode, (enabled) → None
                                                           # reduced-noise mode is limited to ±4 g, so keep it off at ±4 g
accel.set_resolution(14)                                    # Set output resolution, (bits) → None bit
                                                           # 14-bit output (two bytes per axis); 8 = fast read
accel.set_high_pass_output(False, cutoff=0)                 # Set high-pass output, (enabled, cutoff=0) → None
                                                           # disables the high-pass-filtered output; cutoff 0–3 selects the Hz
accel.set_offset(0.0, 0.0, 0.0)                             # Set offsets, (x_g, y_g, z_g) → None g
                                                           # zero-g correction at 2 mg/LSB (±0.256 g range)

x, y, z = accel.read()                                      # Read 3-axis acceleration, () → tuple(float, float, float) g
                                                           # burst read of 0x01–0x06, scaled by the current range
rx, ry, rz = accel.read_raw()                               # Read raw counts, () → tuple(int, int, int) LSB
                                                           # signed 14-bit two's-complement counts
ready = accel.new_data_available()                          # Check new data, () → bool
                                                           # True once STATUS.ZYXDR is set
status = accel.read_status()                                # Read STATUS, () → int
                                                           # STATUS byte (ZYXOW … XDR)
mode = accel.read_system_mode()                             # Read system mode, () → int
                                                           # SYSMOD bits 1:0 — 0 standby, 1 wake, 2 sleep

accel.set_freefall_motion(False, axes=0x07,                 # Configure freefall / motion, (motion, axes=7, threshold_g=0.0, debounce_ms=0, latch=False, debounce_clear=False) → None g, ms
                          threshold_g=0.2, debounce_ms=60,   # freefall on all three axes below 0.2 g for 60 ms
                          latch=True)                        # latches the event flags until they are read
accel.set_transient(axes=0x07, threshold_g=0.3,             # Configure transient detector, (axes=7, threshold_g=0.0, debounce_ms=0, bypass_hpf=False, latch=False) → None g, ms
                    debounce_ms=20)                         # high-pass filtered motion above 0.3 g
accel.set_pulse(single=0x00, double=0x04,                   # Configure tap detector, (single=0, double=0, threshold_g=(0.5, 0.5, 0.5), time_limit_ms=30, latency_ms=100, window_ms=300, latch=False, double_abort=False, lpf=True, hpf=True) → None g, ms
                threshold_g=(0.5, 0.5, 1.0),                 # per-axis thresholds: X/Y 0.5 g, Z 1.0 g
                time_limit_ms=40, latency_ms=100,            # pulse must end within 40 ms; second pulse within 100 ms
                window_ms=300, latch=True)                   # second-pulse window 300 ms; latched flags
accel.set_orientation(True, debounce_ms=100,                # Configure orientation detector, (enabled=True, debounce_ms=0, threshold_deg=45, hysteresis=4, z_lock=4, back_front=1) → None ms, deg
                      threshold_deg=45, hysteresis=4,         # 45° trip angle with ±14° hysteresis
                      z_lock=4, back_front=1)                # Z-lock at 29°, default back/front angles

accel.set_fifo(1, 16)                                       # Set FIFO mode, (mode, watermark=0) → None
                                                           # 1 = circular, 16-sample watermark
accel.set_fifo_trigger(transient=True, orientation=True,   # Set FIFO trigger sources, (transient=False, orientation=False, pulse=False, motion=False) → None
                       pulse=True, motion=True)              # any of the four detectors can trigger the FIFO
accel.set_fifo_gate(True)                                   # Set FIFO gate, (enabled) → None
                                                           # blocks FIFO input around WAKE/SLEEP transitions
accel.set_auto_sleep(True, sleep_rate_hz=12.5,              # Set auto-sleep, (enabled, sleep_rate_hz=50, timeout_s=10, wake_sources=0) → None Hz, s
                     timeout_s=10,                          # sleep at 12.5 Hz after 10 s of inactivity
                     wake_sources=MMA8451QFull.SOURCE_PULSE | MMA8451QFull.SOURCE_FF_MT)  # wake on tap or freefall
accel.set_interrupt_pins(active_high=False, open_drain=False)  # Set interrupt pins, (active_high=False, open_drain=False) → None
                                                           # active-low push-pull (the chip's reset default)

accel.enable_interrupt(MMA8451QFull.SOURCE_ORIENTATION, pin=1)  # Enable interrupt, (source, pin=1) → None
                                                           # sets INT_EN_LNDPRT in CTRL_REG4 and routes it to INT1
accel.enable_interrupt(MMA8451QFull.SOURCE_PULSE, pin=1)  # Enable interrupt, (source, pin=1) → None
                                                           # routes the tap detector to INT1 as well
accel.enable_interrupt(MMA8451QFull.SOURCE_FF_MT, pin=1)   # Enable interrupt, (source, pin=1) → None
                                                           # routes the freefall / motion detector to INT1

level = accel.fifo_level()                                  # Read FIFO level, () → int
                                                           # number of samples currently in the FIFO (0–32)
overflow, watermark, count = accel.read_fifo_status()       # Read FIFO status, () → tuple(bool, bool, int)
                                                           # decoded F_STATUS — reading it clears the FIFO interrupt
samples = accel.read_fifo(4)                                # Read FIFO samples, (count=None) → list(tuple(float, float, float)) g
                                                           # drains up to 4 samples, one transaction per sample

changed, lockout, lapo, back = accel.read_orientation()    # Read orientation, () → tuple(bool, bool, int, bool)
                                                           # (changed, lockout, portrait_landscape, back)
motion_src = accel.read_motion_source()                     # Read motion source, () → int
                                                           # FF_MT_SRC byte — cleared when the events are latched
transient_src = accel.read_transient_source()               # Read transient source, () → int
                                                           # TRANSIENT_SRC byte — clears the interrupt
pulse_src = accel.read_pulse_source()                       # Read pulse source, () → int
                                                           # PULSE_SRC byte — clears the interrupt
irq = accel.poll_interrupt()                                # Poll interrupt status, () → int
                                                           # INT_SOURCE byte; clears every source except data-ready

accel.standby()                                             # Enter standby mode, () → None
                                                           # clears ACTIVE in CTRL_REG1
accel.activate()                                            # Leave standby mode, () → None
                                                           # sets ACTIVE again and waits the turn-on time

ok, dx, dy, dz = accel.self_test()                          # Run self-test, () → tuple(bool, int, int, int)
                                                           # (ok, dx, dy, dz) — count deltas per axis
accel.reset()                                               # Software reset, () → None
                                                           # RST bit; the chip returns to STANDBY with reset values

print('x={:.3f} y={:.3f} z={:.3f} raw={} status={} mode={} irq={}'.format(
    x, y, z, (rx, ry, rz), status, mode, irq))
print('fifo level={} overflow={} watermark={} count={} samples={}'.format(
    level, overflow, watermark, count, samples))
print('orientation={} motion=0x{:02X} transient=0x{:02X} pulse=0x{:02X}'.format(
    (changed, lockout, lapo, back), motion_src, transient_src, pulse_src))
print('self-test ok={} dx={} dy={} dz={}'.format(ok, dx, dy, dz))
