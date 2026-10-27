"""Unit test for the LSM303DLHC — runs without hardware using two I2C mocks.

One mock stands in for the accelerometer (0x19), one for the magnetometer
(0x1E). Verifies init write sequencing, register bit packing, the
accelerometer auto-increment bit, FS / ODR / mode bit fields, data
conversion, magnetometer X,Z,Y reordering, and error cases.

The mock does not model the accelerometer's auto-increment bit: a burst read
of 0x28 with SUB = 0xA8 is served from registers 0xA8–0xAD. The tests preload
those addresses, which also checks that the driver sets bit 7.
"""

import struct

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.accelerometer.lsm303 import LSM303Minimal, LSM303Full, LSM303Source

passed = 0
failed = 0


def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1


def check_eq(label, got, expected):
    global passed, failed
    if got == expected:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL {}: got {!r}, expected {!r}'.format(label, got, expected))
        failed += 1


def check_close(label, got, expected, tol=1e-9):
    check_true('{} (got {!r}, expected {!r})'.format(label, got, expected),
               abs(got - expected) < tol)


def check_raises(label, exc_type, fn):
    global passed, failed
    try:
        fn()
    except exc_type:
        print('PASS', label)
        passed += 1
        return
    except Exception as e:
        print('FAIL {}: wrong exception {!r}'.format(label, e))
        failed += 1
        return
    print('FAIL {}: no exception'.format(label))
    failed += 1


MAG_ID_BYTES = b'\x48\x34\x33'


def make_mocks():
    accel = I2CConnectionMock()
    mag = I2CConnectionMock()
    # Magnetometer identity IRA/IRB/IRC = 'H', '4', '3'.
    mag.set_register(0x0A, 0x48, 0x34, 0x33)
    return accel, mag


def writes_len2(conn):
    """Return single-byte register writes (2-byte payload) in order."""
    return [w for w in conn.writes if len(w) == 2]


def acc_burst(accel, raw_x, raw_y, raw_z):
    """Preload OUT_X_L_A..OUT_Z_H_A at the auto-increment sub-address 0xA8."""
    accel.set_register(0xA8, *(struct.pack('<hhh', raw_x, raw_y, raw_z)))


def mag_burst(mag, x, z, y):
    """Preload OUT_X_H_M..OUT_Y_L_M (X, Z, Y order, high byte first) at 0x03."""
    mag.set_register(0x03, *(struct.pack('>hhh', x, z, y)))


# ---- init sequence ---------------------------------------------------------

accel, mag = make_mocks()
sensor = LSM303Minimal(accel, mag)
check_true('construct_minimal', isinstance(sensor, LSM303Minimal))

check_true('mag_identity_read_3_bytes', bytes([0x0A]) in mag.writes)

# Accelerometer control registers: CTRL2, CTRL3, CTRL5, CTRL6, FIFO_CTRL = 0x00,
# CTRL4 = 0x88 (BDU, HR, ±2 g), CTRL1 = 0x57 (100 Hz, LPen 0, XYZ enabled).
check_eq('init_accel_writes', writes_len2(accel), [
    bytes([0x21, 0x00]),
    bytes([0x22, 0x00]),
    bytes([0x24, 0x00]),
    bytes([0x25, 0x00]),
    bytes([0x2E, 0x00]),
    bytes([0x23, 0x88]),
    bytes([0x20, 0x57]),
])
check_eq('init_mag_writes', writes_len2(mag), [
    bytes([0x00, 0x10]),   # CRA: 15 Hz, temperature off
    bytes([0x01, 0x20]),   # CRB: ±1.3 gauss
    bytes([0x02, 0x00]),   # MR: continuous conversion
])
check_eq('init_mag_registers_stored', mag.registers[0x02], 0x00)

# Magnetometer identity mismatch must raise ValueError.
bad_mag = I2CConnectionMock()
bad_mag.set_register(0x0A, 0x48, 0x34, 0x34)
check_raises('mag_identity_mismatch_raises', ValueError,
             lambda: LSM303Minimal(I2CConnectionMock(), bad_mag))

# ---- accelerometer reads: auto-increment bit, conversion ------------------

accel, mag = make_mocks()
sensor = LSM303Minimal(accel, mag)
acc_burst(accel, 16000, -16000, 8000)
x, y, z = sensor.read_acceleration()
check_close('read_acc_x_1g', x, 1.0)
check_close('read_acc_y_neg_1g', y, -1.0)
check_close('read_acc_z_half_g', z, 0.5)
check_true('burst_uses_auto_increment_subaddress', bytes([0xA8]) in accel.writes)

# Single-register read must not set bit 7.
accel.set_register(0x27, 0x08)
check_eq('status_read_single', sensor._acc_read(0x27, 1), bytes([0x08]))
check_true('single_read_no_autoinc', bytes([0x27]) in accel.writes)

# Left-justified data: low 4 bits are not sensor data in high-resolution mode.
acc_burst(accel, 16000 + 15, 0, 0)
check_close('hr_drops_4_low_bits', sensor.read_acceleration()[0], 1.0)

# Normal mode (10-bit, 4 mg/LSB at ±2 g): 16000 >> 6 = 250 -> 1.0 g.
accel, mag = make_mocks()
sensor = LSM303Full(accel, mag)
sensor.set_mode(1)
acc_burst(accel, 16000, 0, 0)
check_close('normal_mode_1g', sensor.read_acceleration()[0], 1.0)
check_eq('normal_mode_raw', sensor.read_acceleration_raw()[0], 250)

# Low-power mode (8-bit, 16 mg/LSB at ±2 g): 16000 >> 8 = 62 -> 0.992 g.
sensor.set_data_rate(0)
sensor.set_mode(0)
check_close('low_power_mode_scale', sensor.read_acceleration()[0], 0.992)

# High-resolution, ±4 g: 2 mg/LSB, 16000 >> 4 = 1000 -> 2.0 g.
accel, mag = make_mocks()
sensor = LSM303Full(accel, mag)
sensor.set_range(4)
acc_burst(accel, 16000, 0, 0)
check_close('range_4g_scale', sensor.read_acceleration()[0], 2.0)

# Range 16 g: 12 mg/LSB (not 8). 1000 counts -> 12.0 g.
sensor.set_range(16)
acc_burst(accel, 16000, 0, 0)
check_close('range_16g_is_12mg_per_lsb', sensor.read_acceleration()[0], 12.0)
sensor.set_range(2)

# Sign extension: -16000 >> 4 = -1000 -> -1.0 g.
acc_burst(accel, -16000, 0, 0)
check_close('negative_sign_extend', sensor.read_acceleration()[0], -1.0)

# ---- magnetometer reads: X,Z,Y order and µT conversion --------------------

accel, mag = make_mocks()
sensor = LSM303Minimal(accel, mag)
# X = 1100 counts (1100 LSB/gauss at ±1.3 gauss) -> 100 µT.
# Z = 490 counts (980 LSB/gauss) -> 50 µT.
# Y = -550 counts (1100 LSB/gauss) -> -50 µT.
mag_burst(mag, 1100, 490, -550)
mx, my, mz = sensor.read_magnetic()
check_close('mag_x_100uT', mx, 100.0)
check_close('mag_y_minus_50uT', my, -50.0)
check_close('mag_z_50uT', mz, 50.0)

# read_magnetic_raw (Full): counts reordered to X, Y, Z.
full = LSM303Full(accel, mag)
check_eq('mag_raw_reorder', full.read_magnetic_raw(), (1100, -550, 490))

# ±8.1 gauss: X/Y 230 LSB/gauss, Z 205 LSB/gauss.
full.set_mag_range(810)
mag_burst(mag, 230, 205, 0)
mx, my, mz = full.read_magnetic()
check_close('mag_range_810_x', mx, 100.0)
check_close('mag_range_810_z', mz, 100.0)
full.set_mag_range(130)

# ---- Full: configuration bit fields --------------------------------------

accel, mag = make_mocks()
full = LSM303Full(accel, mag)

# set_range: FS<1:0> in CTRL4 bits 5:4, HR and BDU preserved.
full.set_range(8)
check_eq('set_range_8_ctrl4', accel.registers[0x23], 0xA8)
check_raises('set_range_invalid', ValueError, lambda: full.set_range(3))

# set_axes: Zen bit 2, Yen bit 1, Xen bit 0 in CTRL1; ODR preserved.
full.set_axes(x=True, y=False, z=True)
check_eq('set_axes_ctrl1', accel.registers[0x20], 0x55)

# set_data_rate: ODR<3:0> in CTRL1 bits 7:4, axes preserved.
full.set_data_rate(50)
check_eq('set_data_rate_50_ctrl1', accel.registers[0x20], 0x45)
full.set_data_rate(1344)   # normal / high-resolution only
check_eq('set_data_rate_1344_ctrl1', accel.registers[0x20], 0x95)
check_raises('1620_rejected_in_hr', ValueError, lambda: full.set_data_rate(1620))
check_raises('5376_rejected_in_hr', ValueError, lambda: full.set_data_rate(5376))
check_raises('rate_invalid', ValueError, lambda: full.set_data_rate(2))

# set_mode: LPen (CTRL1 bit 3) and HR (CTRL4 bit 3).
full.set_mode(1)
check_eq('mode_normal_ctrl1', accel.registers[0x20] & 0x08, 0x00)
check_eq('mode_normal_ctrl4', accel.registers[0x23] & 0x08, 0x00)
check_raises('1344_rejected_in_lp', ValueError, lambda: full.set_mode(0))
full.set_data_rate(50)
full.set_mode(0)
check_eq('mode_lp_lpen_set', accel.registers[0x20] & 0x08, 0x08)
check_eq('mode_lp_hr_clear', accel.registers[0x23] & 0x08, 0x00)
full.set_data_rate(1620)
check_eq('set_data_rate_1620_ctrl1', accel.registers[0x20] & 0xF0, 0x80)
full.set_data_rate(5376)
check_eq('set_data_rate_5376_ctrl1', accel.registers[0x20] & 0xF0, 0x90)
check_raises('1344_rejected_in_lp_rate', ValueError, lambda: full.set_data_rate(1344))
check_raises('mode_invalid', ValueError, lambda: full.set_mode(3))
full.set_data_rate(50)                   # 5376 Hz blocks leaving low-power mode
full.set_mode(2)
check_eq('mode_hr_hr_set', accel.registers[0x23] & 0x08, 0x08)
check_eq('mode_hr_lpen_clear', accel.registers[0x20] & 0x08, 0x00)
check_raises('mode_hr_blocks_5376_rate', ValueError, lambda: full.set_data_rate(5376))
full.set_data_rate(0)
check_eq('power_down_ctrl1_odr_zero', accel.registers[0x20] & 0xF0, 0x00)
full.set_mode(1)   # allowed: nothing to conflict with after power-down
full.set_data_rate(100)

# set_high_pass: CTRL2 full write. mode=1, cutoff=2, filter_output, on_ia1.
full.set_high_pass(mode=1, cutoff=2, filter_output=True, on_ia1=True)
check_eq('high_pass_ctrl2', accel.registers[0x21], 0x69)
check_raises('high_pass_mode_invalid', ValueError, lambda: full.set_high_pass(mode=4))

# REFERENCE_A round trip.
accel.set_register(0x26, 0x5A)
check_eq('reset_high_pass_reads_reference', full.reset_high_pass(), 0x5A)
full.set_reference(0x33)
check_eq('set_reference_writes', accel.registers[0x26], 0x33)
check_raises('set_reference_range', ValueError, lambda: full.set_reference(256))

# ---- FIFO ----------------------------------------------------------------

full.set_fifo(2, watermark=16, trigger_int2=True)
check_eq('fifo_ctrl_stream_wtm16_trig_int2', accel.registers[0x2E], 0xB0)
check_eq('fifo_en_set_for_stream', accel.registers[0x24] & 0x40, 0x40)
full.set_fifo(0)
check_eq('fifo_ctrl_bypass', accel.registers[0x2E], 0x00)
check_eq('fifo_en_clear_for_bypass', accel.registers[0x24] & 0x40, 0x00)
check_raises('fifo_watermark_range', ValueError, lambda: full.set_fifo(1, watermark=32))

accel.set_register(0x2F, 0x83)
check_eq('fifo_level_3', full.fifo_level(), 3)
accel.set_register(0x2F, 0xA5)
check_eq('fifo_status_decoded', full.read_fifo_status(), (True, False, True, 5))

# read_fifo: one 6-byte burst per sample set; each sample is 1 g on X.
accel.set_register(0x2F, 0x02)
acc_burst(accel, 16000, 0, 0)
samples = full.read_fifo()
check_eq('read_fifo_count', len(samples), 2)
# Range is ±8 g and mode is normal here: 16000 >> 6 = 250 counts * 16 mg = 4.0 g.
check_close('read_fifo_sample_x', samples[0][0], 4.0)
check_eq('read_fifo_limited', len(full.read_fifo(count=1)), 1)

# ---- status and new data -------------------------------------------------

accel.set_register(0x27, 0x08)
check_true('new_data_available_true', full.new_data_available())
accel.set_register(0x27, 0x00)
check_true('new_data_available_false', not full.new_data_available())
accel.set_register(0x27, 0x5C)
check_eq('read_status_byte', full.read_status(), 0x5C)

# ---- inertial interrupt generators --------------------------------------

full.set_data_rate(100)
full.set_range(2)
full.set_motion_interrupt(1, 0x3F, 1.0, 100, and_mode=False)
check_eq('motion_int1_cfg', accel.registers[0x30], 0x3F)
check_eq('motion_int1_ths', accel.registers[0x32], 64)     # 1.0 g * 128 / 2 g
check_eq('motion_int1_duration', accel.registers[0x33], 10)  # 100 ms * 100 Hz

# Free-fall: INT1_CFG = 0x95 (AOI, ZLIE, YLIE, XLIE).
full.set_free_fall(generator=1, threshold_g=0.35, duration_ms=30)
check_eq('free_fall_cfg_0x95', accel.registers[0x30], 0x95)
check_eq('free_fall_ths', accel.registers[0x32], 22)       # 0.35 g * 128 / 2 g = 22.4
check_eq('free_fall_duration', accel.registers[0x33], 3)   # 30 ms * 100 Hz / 1000

full.set_motion_interrupt(2, 0x01, 0.5, 0)
check_eq('motion_int2_cfg', accel.registers[0x34], 0x01)
check_eq('motion_int2_ths', accel.registers[0x36], 32)
check_eq('motion_int2_zero_duration', accel.registers[0x37], 0)
check_raises('motion_events_invalid', ValueError, lambda: full.set_motion_interrupt(1, 0x40, 1.0))
check_raises('generator_invalid', ValueError, lambda: full.set_motion_interrupt(3, 0x01, 1.0))

# 6D orientation: 6D bit set, D4D_INT1 (CTRL5 bit 2) when four_d.
full.set_orientation(1, position=False, four_d=True)
check_eq('orientation_movement_cfg', accel.registers[0x30], 0x7F)
check_eq('orientation_d4d1_set', accel.registers[0x24] & 0x04, 0x04)
full.set_orientation(2, position=True, four_d=False)
check_eq('orientation_position_cfg', accel.registers[0x34], 0xFF)
check_eq('orientation_d4d2_clear', accel.registers[0x24] & 0x01, 0x00)

# Latch bits: LIR_INT1 bit 3, LIR_INT2 bit 1.
full.set_latch(1, True)
check_eq('latch_int1', accel.registers[0x24] & 0x08, 0x08)
full.set_latch(2, True)
check_eq('latch_int2', accel.registers[0x24] & 0x02, 0x02)
full.set_latch(1, False)
check_eq('latch_int1_cleared', accel.registers[0x24] & 0x08, 0x00)

accel.set_register(0x31, 0x40)
check_eq('read_interrupt_source_int1', full.read_interrupt_source(1), 0x40)
accel.set_register(0x35, 0x10)
check_eq('read_interrupt_source_int2', full.read_interrupt_source(2), 0x10)

# ---- click ----------------------------------------------------------------

# single: X (mask 1) -> XS bit 0 ; double: Z (mask 4) -> ZD bit 5.
full.set_click(single=1, double=4, threshold_g=0.5, time_limit_ms=10, latency_ms=20, window_ms=50)
check_eq('click_cfg_single_x_double_z', accel.registers[0x38], 0x21)
check_eq('click_ths', accel.registers[0x3A], 32)           # 0.5 g * 128 / 2 g
check_eq('click_time_limit', accel.registers[0x3B], 1)     # 10 ms * 100 / 1000
check_eq('click_latency', accel.registers[0x3C], 2)        # 20 ms
check_eq('click_window', accel.registers[0x3D], 5)         # 50 ms
full.set_click(single=2, double=0)
check_eq('click_cfg_single_y', accel.registers[0x38], 0x04)   # YS bit 2
check_raises('click_mask_invalid', ValueError, lambda: full.set_click(single=8))

accel.set_register(0x39, 0x7F)
check_eq('read_click_source', full.read_click_source(), 0x7F)

# ---- interrupt polarity and routing --------------------------------------

full.set_interrupt_polarity(True)
check_eq('polarity_active_low', accel.registers[0x25] & 0x02, 0x02)
full.set_interrupt_polarity(False)
check_eq('polarity_active_high', accel.registers[0x25] & 0x02, 0x00)

accel, mag = make_mocks()
full = LSM303Full(accel, mag)

full.enable_interrupt(LSM303Source.SOURCE_DATA_READY, pin=2)     # pin ignored: INT1 only
check_eq('route_drdy_on_int1_ctrl3', accel.registers[0x22], 0x10)
check_eq('route_drdy_ignores_pin2', accel.registers[0x25], 0x00)
full.enable_interrupt(LSM303Source.SOURCE_IA1, pin=2)
check_eq('route_ia1_pin2_ctrl6', accel.registers[0x25], 0x40)
full.enable_interrupt(LSM303Source.SOURCE_IA1, pin=1)
check_eq('route_ia1_pin1_ctrl3', accel.registers[0x22], 0x50)
full.enable_interrupt(LSM303Source.SOURCE_BOOT)
check_eq('route_boot_ctrl6_bit4', accel.registers[0x25] & 0x10, 0x10)
full.disable_interrupt(LSM303Source.SOURCE_IA1, pin=2)
check_eq('unroute_ia1_pin2', accel.registers[0x25] & 0x40, 0x00)
check_raises('mag_drdy_not_routable', ValueError,
             lambda: full.enable_interrupt(LSM303Source.SOURCE_MAG_DATA_READY))
check_raises('unknown_source', ValueError, lambda: full.enable_interrupt(0x0300))
check_raises('pin_invalid', ValueError, lambda: full.enable_interrupt(LSM303Source.SOURCE_CLICK, pin=3))

# poll_interrupt: DATA_READY (STATUS ZYXDA) + IA1 (INT1_SOURCE IA) + mag DRDY.
accel, mag = make_mocks()
full = LSM303Full(accel, mag)
accel.set_register(0x22, 0x10 | 0x40)   # DRDY and IA1 on INT1
accel.set_register(0x27, 0x08)          # ZYXDA
accel.set_register(0x31, 0x40)          # INT1_SOURCE IA
mag.set_register(0x09, 0x01)            # DRDY
check_eq('poll_interrupt_combined', full.poll_interrupt(), 0x01 | 0x08 | 0x80)
check_true('poll_reads_int1_source', bytes([0x31]) in accel.writes)

# BOOT is pin-only and never reported; click reported via CLICK_SRC IA.
accel, mag = make_mocks()
full = LSM303Full(accel, mag)
accel.set_register(0x25, 0x10 | 0x80)   # BOOT on INT2 and click on INT2
accel.set_register(0x39, 0x40)          # CLICK_SRC IA
check_eq('poll_boot_not_reported_click_reported', full.poll_interrupt(), LSM303Source.SOURCE_CLICK)

# ---- mag configuration ----------------------------------------------------

accel, mag = make_mocks()
full = LSM303Full(accel, mag)
full.set_mag_range(250)
check_eq('mag_range_250_crb', mag.registers[0x01], 0x60)   # GN=011 in bits 7:5
check_raises('mag_range_invalid', ValueError, lambda: full.set_mag_range(100))

mag.set_register(0x09, 0x01)
check_true('mag_data_ready_true', full.mag_data_ready())
mag.set_register(0x09, 0x03)
check_eq('read_mag_status', full.read_mag_status(), (True, True))
check_eq('read_mag_identity', full.read_mag_identity(), MAG_ID_BYTES)

# Temperature: TEMP_EN is set on first use; 12-bit value, 8 LSB/°C.
check_eq('temp_en_off_initially', mag.registers[0x00] & 0x80, 0x00)
mag.set_register(0x31, 0xFF, 0x00)       # -16 counts (12-bit) left-justified -> -2.0 °C
check_close('temperature_delta', full.read_temperature_delta(), -2.0)
check_eq('temp_en_set_on_first_use', mag.registers[0x00] & 0x80, 0x80)
mag.set_register(0x31, 0x00, 0x80)       # +8 counts -> +1.0 °C
check_close('temperature_delta_positive', full.read_temperature_delta(), 1.0)

full.set_mag_data_rate(75)
check_eq('mag_rate_75_keeps_temp_en', mag.registers[0x00], 0x80 | (6 << 2))
check_raises('mag_rate_invalid', ValueError, lambda: full.set_mag_data_rate(5))

full.set_mag_mode(1)
check_eq('mag_mode_single', mag.registers[0x02], 1)
full.set_mag_mode(2)
check_eq('mag_mode_sleep', mag.registers[0x02], 2)
check_raises('mag_mode_invalid', ValueError, lambda: full.set_mag_mode(3))

# ---- heading ----------------------------------------------------------------

accel, mag = make_mocks()
full = LSM303Full(accel, mag)
acc_burst(accel, 0, 0, 16000)            # level: gravity on Z, no roll/pitch
# X = 0, Z = 0, Y = +1100 (1100 LSB/gauss -> +100 µT). Heading 90 deg.
mag_burst(mag, 0, 0, 1100)
check_close('heading_flat_90', full.heading(tilt_compensate=False), 90.0)
check_close('heading_tilt_level_90', full.heading(tilt_compensate=True), 90.0)
mag_burst(mag, -1100, 0, 0)
check_close('heading_flat_180', full.heading(tilt_compensate=False), 180.0)
mag_burst(mag, 0, 0, -1100)
check_close('heading_flat_270', full.heading(tilt_compensate=False), 270.0)
mag_burst(mag, 1100, 0, 0)
check_close('heading_flat_0', full.heading(tilt_compensate=False), 0.0)

# ---- reboot ----------------------------------------------------------------

accel, mag = make_mocks()
full = LSM303Full(accel, mag)
full.reboot()
check_eq('reboot_sets_boot', accel.registers[0x24] & 0x80, 0x80)

# ---- interrupts: edge path, polling path ----------------------------------


class FakeInputPin:
    """Records on_edge / off_edge and lets the test fire the handler."""
    CHANGE = 3

    def __init__(self):
        self.handlers = []
        self.last_trigger = None

    def on_edge(self, handler, trigger=None):
        self.handlers.append(handler)
        self.last_trigger = trigger

    def off_edge(self, handler):
        if handler in self.handlers:
            self.handlers.remove(handler)

    def fire(self):
        for h in list(self.handlers):
            h()


accel, mag = make_mocks()
full = LSM303Full(accel, mag)
pin = FakeInputPin()
accel.int_pin = pin
received = []
full.on_interrupt(lambda status: received.append(status))
check_eq('edge_handler_registered', len(pin.handlers), 1)
check_eq('edge_trigger_change', pin.last_trigger, FakeInputPin.CHANGE)

accel.set_register(0x22, 0x10)          # DATA_READY routed to INT1
accel.set_register(0x27, 0x08)          # new sample
pin.fire()
check_eq('edge_callback_status', received, [LSM303Source.SOURCE_DATA_READY])

accel.set_register(0x27, 0x00)
pin.fire()
check_eq('edge_no_callback_when_status_zero', len(received), 1)

full.off_interrupt()
check_eq('off_interrupt_removes_edge_handler', len(pin.handlers), 0)

# Polling fallback: no int_pin -> thread started; off_interrupt stops it.
accel, mag = make_mocks()
full = LSM303Full(accel, mag)
polled = []
full.on_interrupt(lambda status: polled.append(status))
check_true('poll_fallback_listening', full._listening)
full.off_interrupt()
check_true('poll_fallback_stopped', not full._listening)

# _dispatch without a thread: direct check of the callback path.
accel, mag = make_mocks()
full = LSM303Full(accel, mag)
seen = []
full._callbacks[1] = lambda status: seen.append(status)
accel.set_register(0x22, 0x10)
accel.set_register(0x27, 0x08)
full._dispatch()
check_eq('dispatch_calls_callback_with_status', seen, [LSM303Source.SOURCE_DATA_READY])

check_raises('on_interrupt_pin_invalid', ValueError, lambda: full.on_interrupt(lambda s: None, pin=0))

# ---- Full extends Minimal (inherited behaviour present) -------------------

check_true('full_is_minimal', isinstance(full, LSM303Minimal))
check_true('full_has_read_acceleration', callable(getattr(full, 'read_acceleration', None)))

print('===DONE: {} passed, {} failed==='.format(passed, failed))
