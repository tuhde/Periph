"""Complete example for the LIS3DH — exercise every method in the public API.

Configures range, mode, data rate, axes, power-down, raw / status /
temperature / auxiliary-ADC reads, the high-pass filter, the FIFO, both
interrupt generators, orientation, click, activity, interrupt routing and
delivery, reboot, SDO pull-up control and the self-test.
"""

from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.accelerometer.lis3dh import LIS3DHFull, LIS3DHSource

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, 0x18)
accel = LIS3DHFull(connection)                              # Create LIS3DH Full driver, (connection)

# --- Range, mode, data rate, axes ---
accel.set_range(8)                                          # Set full-scale range, (range_g=2) → None g
                                                            # reprograms FS in CTRL_REG4; sensitivity becomes 4 mg/LSB
accel.set_mode(1)                                           # Set operating mode, (mode=2) → None
                                                            # 12-bit high-resolution → 10-bit normal (LPen=0, HR=0)
accel.set_data_rate(400)                                    # Set output data rate, (rate_hz=100) → None Hz
                                                            # ODR = 400 Hz; waits one ODR period for the converter
accel.set_axes(True, True, True)                            # Enable measurement axes, (x=True, y=True, z=True) → None
                                                            # Xen/Yen/Zen all set in CTRL_REG1

# --- Power cycle and restore the defaults ---
accel.power_down()                                          # Stop conversions, () → None
                                                            # ODR = 0000; the control registers are retained
accel.set_data_rate(100)                                    # Set output data rate, (rate_hz=0) → None Hz
                                                            # leave power-down again at 100 Hz
accel.set_mode(2)                                           # Set operating mode, (mode=1) → None
                                                            # back to high-resolution 12-bit mode
accel.set_range(2)                                          # Set full-scale range, (range_g=8) → None g
                                                            # back to ±2 g (1 mg/LSB)

# --- Reads ---
x, y, z = accel.read()                                      # Read 3-axis acceleration, () → tuple(float, float, float) g
                                                            # 6-byte burst of 0x28–0x2D, scaled to g
rx, ry, rz = accel.read_raw()                               # Read raw counts, () → tuple(int, int, int) LSB
                                                            # signed counts at the current resolution (12-bit here)
ready = accel.new_data_available()                          # Check new data, () → bool
                                                            # STATUS_REG.ZYXDA — a fresh sample set is ready
status = accel.read_status()                                # Read STATUS register, () → int
                                                            # overrun and data-available flags

# --- Auxiliary ADC and temperature ---
accel.enable_adc(True)                                      # Enable auxiliary ADC, (enabled) → None
                                                            # sets ADC_EN in TEMP_CFG_REG
v1 = accel.read_adc(1)                                      # Read aux ADC channel, (channel=1) → float V
                                                            # 10-bit conversion mapped onto the ±400 mV input range
v2 = accel.read_adc(2)                                      # Read aux ADC channel, (channel=2) → float V
                                                            # second auxiliary input
v3 = accel.read_adc(3)                                      # Read aux ADC channel, (channel=3) → float V
                                                            # temperature input while TEMP_EN = 0
accel.enable_adc(False)                                     # Disable auxiliary ADC, (enabled) → None
                                                            # clears ADC_EN again
temp = accel.read_temperature_delta()                       # Read relative temperature, () → int °C
                                                            # enables ADC_EN + TEMP_EN on first use, 1 digit/°C

# --- High-pass filter ---
accel.set_high_pass(mode=1, cutoff=2, filter_output=True,
                    on_ia1=True, on_ia2=False,
                    on_click=True)                           # Configure high-pass filter, (mode=0, cutoff=0, filter_output=False, on_ia1=False, on_ia2=False, on_click=False) → None
                                                            # HPM=01, HPCF=10, filtered data routed to output, IA1 and click
ref = accel.reset_high_pass()                               # Reset high-pass filter, () → int
                                                            # reading REFERENCE resets the filter in HPM = 00
accel.set_reference(0x40)                                   # Set high-pass reference, (value=0) → None
                                                            # REFERENCE = 0x40 for HPM = 01

# --- FIFO ---
accel.set_fifo(1, watermark=16)                             # Configure FIFO, (mode=0, watermark=0, trigger_int2=False) → None
                                                            # FIFO mode with a 16-sample watermark; FIFO_EN set
level = accel.fifo_level()                                  # Read FIFO level, () → int
                                                            # FSS<4:0> — unread sample sets in the buffer
watermark, overrun, empty, lvl = accel.read_fifo_status()   # Read FIFO status, () → tuple(bool, bool, bool, int)
                                                            # WTM / OVRN_FIFO / EMPTY flags plus the level
samples = accel.read_fifo()                                 # Drain FIFO, (count=None) → list(tuple(float, float, float)) g
                                                            # one 6-byte burst per sample set, oldest first
accel.set_fifo(0)                                           # Configure FIFO, (mode=1) → None
                                                            # back to bypass, which empties the buffer

# --- Inertial interrupt generators ---
accel.set_motion_interrupt(1, 0x3F, 0.5, 30, True)         # Configure motion interrupt, (generator=1, events=0, threshold_g=0, duration_ms=0, and_mode=False) → None
                                                            # generator 1, all six axis events, 0.5 g, 30 ms, AND-combined
accel.set_free_fall(generator=1, threshold_g=0.35,
                     duration_ms=30)                         # Configure free-fall, (generator=1, threshold_g=0.35, duration_ms=30) → None
                                                            # INT1_CFG = 0x95: all axes low, AND mode
accel.set_orientation(generator=1, position=False,
                      four_d=False)                         # Configure orientation, (generator=1, position=False, four_d=False) → None
                                                            # 6D movement recognition (a one-ODR pulse)
accel.set_latch(1, True)                                    # Latch generator 1, (generator=1, enabled=True) → None
                                                            # the interrupt stays high until INT1_SRC is read
src1 = accel.read_interrupt_source(1)                      # Read interrupt source, (generator=1) → int
                                                            # INT1_SRC — reading it clears the interrupt
accel.set_latch(1, False)                                   # Latch generator 1, (generator=1, enabled=True) → None
                                                            # back to a self-resetting interrupt

# --- Click detection ---
accel.set_click(single=0x01, double=0x04, threshold_g=0.5,
                time_limit_ms=10, latency_ms=20,
                window_ms=50, latch=False)                   # Configure click detection, (single=0, double=0, threshold_g=0.5, time_limit_ms=10, latency_ms=20, window_ms=50, latch=False) → None
                                                            # single click on X, double click on Z
click = accel.read_click_source()                           # Read click source, () → int
                                                            # CLICK_SRC — reading it clears the click interrupt

# --- Sleep-to-wake activity ---
accel.set_activity(0.5, 100)                                # Configure activity, (threshold_g=0, duration_ms=0) → None
                                                            # ACT_THS / ACT_DUR; pair with SOURCE_ACTIVITY

# --- Interrupt routing and delivery ---
accel.set_interrupt_polarity(False)                         # Set interrupt polarity, (active_low=False) → None
                                                            # INT1 and INT2 stay active-high (the reset default)
accel.enable_interrupt(LIS3DHSource.SOURCE_DATA_READY, 1)    # Enable interrupt source, (source, pin=1) → None
                                                            # routes the new-data event to INT1
accel.enable_interrupt(LIS3DHSource.SOURCE_CLICK, 1)        # Enable interrupt source, (source, pin=1) → None
                                                            # routes the click event to INT1 too
accel.disable_interrupt(LIS3DHSource.SOURCE_DATA_READY, 1)  # Disable interrupt source, (source, pin=1) → None
                                                            # removes the new-data event from INT1


def on_int(status):
    """Print the combined interrupt status."""
    print('interrupt status: 0x{:04X}'.format(status))


accel.on_interrupt(on_int, 1)                                # Subscribe to INT1, (callback, pin=1) → None
                                                            # the callback receives the combined status int
accel.off_interrupt(1)                                      # Unsubscribe from INT1, (pin=1) → None
                                                            # removes this chip's handler from the INT pin
status = accel.poll_interrupt()                             # Poll interrupt status, () → int
                                                            # reads the source registers of every enabled source

# --- System ---
accel.set_sdo_pullup(True)                                  # Set SDO pull-up, (connected=True) → None
                                                            # reconnects the pull-up on SDO/SA0
accel.reboot()                                              # Reboot memory content, () → None
                                                            # BOOT = 1, then the Initialization Sequence runs again
ok = accel.self_test(1)                                     # Run self-test, (mode=1) → bool
                                                            # electrostatic self-test 0; True when every axis shows 17-360 LSb
print('self-test:', 'PASS' if ok else 'FAIL')
