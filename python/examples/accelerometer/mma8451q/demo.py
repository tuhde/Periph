"""Demo for the MMA8451Q — orientation, tap and freefall monitor with Auto-SLEEP.

Configure ±4 g at 100 Hz with high-resolution oversampling, run the
self-test once, arm the orientation, double-tap-on-Z and freefall
detectors, enable Auto-SLEEP after 10 s, put the FIFO into circular mode
with a 16-sample watermark and route the three detectors to INT1. Every
250 ms print the acceleration and the power mode; report orientation
changes, double taps and freefall events as they arrive. After 60 s drain
the FIFO and print the sample count and the mean acceleration.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.accelerometer.mma8451q import MMA8451QFull

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, 0x1C)
accel = MMA8451QFull(connection)                             # Create MMA8451Q driver, (connection)

# --- Configure ±4 g / 100 Hz / high-resolution oversampling ---
# ±4 g leaves headroom for shocks while keeping 2048 counts/g resolution.
# High-resolution oversampling averages 4 samples internally, which lowers
# the noise — important here because the demo detects small freefall and
# tap events against gravity.
accel.set_range(4)                                          # Set measurement range, (range_g) → None g
                                                           # ±4 g; sensitivity 2048 counts/g
accel.set_data_rate(100)                                    # Set output data rate, (rate_hz) → None Hz
                                                           # 100 Hz — fast enough to catch a 40 ms tap
accel.set_oversampling(2)                                   # Set oversampling mode, (mode, sleep_mode=None) → None
                                                           # 2 = high resolution

# --- Run the electrostatic self-test once ---
# The self-test checks that the MEMS sense axis actually moves under the
# electrostatic force. A failing result means the readings below cannot be
# trusted, so it is printed prominently.
ok, dx, dy, dz = accel.self_test()                          # Run self-test, () → tuple(bool, int, int, int)
                                                           # (ok, dx, dy, dz) — count deltas per axis
print('self-test: ok={} dx={} dy={} dz={}'.format(ok, dx, dy, dz))

# --- Arm the orientation, double-tap and freefall detectors ---
# Orientation latches the new-orientation flag so a change is not lost
# between the 250 ms polls. The double tap is restricted to Z (the board
# is tapped flat on a table), with a looser Z threshold because the
# tapping motion is along the board normal. Freefall fires when all three
# axes drop below 0.2 g — the classic "dropped it" signature.
accel.set_orientation(True, debounce_ms=100)                # Configure orientation detector, (enabled=True, debounce_ms=0, threshold_deg=45, hysteresis=4, z_lock=4, back_front=1) → None ms, deg
                                                           # 100 ms debounce; 45° trip angle
accel.set_pulse(double=0x04, threshold_g=(0.5, 0.5, 1.0),    # Configure tap detector, (single=0, double=0, threshold_g=(0.5, 0.5, 0.5), time_limit_ms=30, latency_ms=100, window_ms=300, latch=False, double_abort=False, lpf=True, hpf=True) → None g, ms
                time_limit_ms=40, latency_ms=100,            # 40 ms time limit, 100 ms latency
                window_ms=300, latch=True)                   # 300 ms second-pulse window; latched flags
accel.set_freefall_motion(motion=False, threshold_g=0.2,     # Configure freefall / motion, (motion, axes=7, threshold_g=0.0, debounce_ms=0, latch=False, debounce_clear=False) → None g, ms
                          debounce_ms=60, latch=True)        # freefall below 0.2 g for 60 ms on all axes

# --- Enable Auto-SLEEP and route the detectors to INT1 ---
# Auto-SLEEP drops the ODR to 12.5 Hz after 10 s of inactivity to save
# power; a tap or a freefall event wakes the chip again. The FIFO stays in
# circular mode so the last 16 samples survive the sleep transitions.
accel.set_auto_sleep(True, sleep_rate_hz=12.5,              # Set auto-sleep, (enabled, sleep_rate_hz=50, timeout_s=10, wake_sources=0) → None Hz, s
                     timeout_s=10,                          # sleep after 10 s of inactivity
                     wake_sources=MMA8451QFull.SOURCE_PULSE | MMA8451QFull.SOURCE_FF_MT)  # wake on tap or freefall
accel.enable_interrupt(MMA8451QFull.SOURCE_ORIENTATION, pin=1)  # Enable interrupt, (source, pin=1) → None
                                                           # orientation change -> INT1
accel.enable_interrupt(MMA8451QFull.SOURCE_PULSE, pin=1)  # Enable interrupt, (source, pin=1) → None
                                                           # double tap -> INT1
accel.enable_interrupt(MMA8451QFull.SOURCE_FF_MT, pin=1)   # Enable interrupt, (source, pin=1) → None
                                                           # freefall -> INT1
accel.set_fifo(1, 16)                                       # Set FIFO mode, (mode, watermark=0) → None
                                                           # 1 = circular, 16-sample watermark

# --- 60-second monitoring loop ---
# The user is expected to rotate the board, double-tap it and drop it
# during the 60 s window. Between events the magnitude sits at ≈1.00 g
# (gravity) and the chip occasionally drops into SLEEP after 10 s of
# stillness — the power mode is printed so that transition is visible.
start = time.ticks_ms()
last_poll = start
mode_names = ('STANDBY', 'WAKE', 'SLEEP')

while time.ticks_diff(time.ticks_ms(), start) < 60_000:
    now = time.ticks_ms()

    if time.ticks_diff(now, last_poll) >= 250:
        x, y, z = accel.read()                               # Read 3-axis acceleration, () → tuple(float, float, float) g
        mode = accel.read_system_mode()                      # Read system mode, () → int
                                                           # SYSMOD bits 1:0 — 0 standby, 1 wake, 2 sleep
        print('{:5d}  x={:+.3f}  y={:+.3f}  z={:+.3f}  {}'.format(
            time.ticks_diff(now, start) // 1000, x, y, z, mode_names[mode]))

        irq = accel.poll_interrupt()                         # Poll interrupt status, () → int
                                                           # INT_SOURCE byte; clears the per-source flags
        if irq & MMA8451QFull.SOURCE_ORIENTATION:
            changed, lockout, lapo, back = accel.read_orientation()  # Read orientation, () → tuple(bool, bool, int, bool)
                                                           # (changed, lockout, portrait_landscape, back)
            print('{:5d}  ORIENTATION changed  lap={} back={}'.format(
                time.ticks_diff(now, start), lapo, back))
        if irq & MMA8451QFull.SOURCE_PULSE:
            pulse = accel.read_pulse_source()                 # Read pulse source, () → int
                                                           # PULSE_SRC byte — clears the interrupt
            print('{:5d}  DOUBLE TAP  src=0x{:02X}'.format(
                time.ticks_diff(now, start), pulse))
        if irq & MMA8451QFull.SOURCE_FF_MT:
            motion = accel.read_motion_source()               # Read motion source, () → int
                                                           # FF_MT_SRC byte — cleared when the events are latched
            print('{:5d}  FREE FALL  src=0x{:02X}'.format(
                time.ticks_diff(now, start), motion))
        last_poll = now

    time.sleep_ms(10)

# --- Drain the FIFO and summarise ---
# The circular FIFO holds the last 16 samples regardless of the sleep
# transitions, so the mean acceleration over that window shows how much
# the board moved just before the demo ended.
samples = accel.read_fifo()                                  # Read FIFO samples, (count=None) → list(tuple(float, float, float)) g
                                                           # drains every sample currently in the FIFO
print('FIFO samples: {}'.format(len(samples)))
if samples:
    mean_x = sum(s[0] for s in samples) / len(samples)
    mean_y = sum(s[1] for s in samples) / len(samples)
    mean_z = sum(s[2] for s in samples) / len(samples)
    print('mean acceleration: x={:+.3f} y={:+.3f} z={:+.3f} g'.format(
        mean_x, mean_y, mean_z))

print('done')
