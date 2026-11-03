"""Unit test for the LIS3DH — runs without hardware using the I2C mock.

Verifies the Minimal driver's initialization sequence (WHO_AM_I check,
the six control-register clears, CTRL_REG4/CTRL_REG1 defaults), the
auto-increment sub-address used for the 6-byte burst, and the
left-justified 16-bit sign-extension and sensitivity scaling, plus the
Full driver's configuration, FIFO, interrupt and self-test behaviour.
"""

import sys
from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.accelerometer.lis3dh import LIS3DHMinimal, LIS3DHFull, LIS3DHSource

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


def last_write(mock, reg):
    """Return the last value written to `reg`, or None."""
    writes = [w for w in mock.writes if len(w) == 2 and w[0] == reg]
    return writes[-1][1] if writes else None


def fresh_mock():
    """Return a mock preloaded with a valid WHO_AM_I and one sample."""
    m = I2CConnectionMock()
    m.set_register(LIS3DHMinimal._REG_WHO_AM_I, 0x33)
    # x = 0x3E80 -> +16000 -> 1000 counts (1.000 g at 1 mg/LSB),
    # y = 0x0000 -> 0 g, z = 0xE0C0 -> -8000 -> -500 counts (-0.500 g).
    m.set_register(LIS3DHMinimal._REG_OUT_X_L | 0x80,
                   0x80, 0x3E,    # X = 0x3E80
                   0x00, 0x00,    # Y = 0x0000
                   0xC0, 0xE0)    # Z = 0xE0C0
    return m


# --- Minimal: initialization sequence -------------------------------------

mock = fresh_mock()
accel = LIS3DHMinimal(mock)
check_true('construct_minimal', True)

# Step 3: control registers cleared (the chip has no soft reset).
for reg in (LIS3DHMinimal._REG_CTRL_REG2, LIS3DHMinimal._REG_CTRL_REG3,
            LIS3DHMinimal._REG_CTRL_REG5, LIS3DHMinimal._REG_CTRL_REG6,
            LIS3DHMinimal._REG_FIFO_CTRL, LIS3DHMinimal._REG_TEMP_CFG):
    check_true('init_clears_0x{:02X}'.format(reg), last_write(mock, reg) == 0x00)

# Steps 4/5: CTRL_REG4 = 0x88 (BDU, ±2 g, HR), CTRL_REG1 = 0x57 (100 Hz, axes on).
check_true('init_ctrl_reg4_0x88', last_write(mock, LIS3DHMinimal._REG_CTRL_REG4) == 0x88)
check_true('init_ctrl_reg1_0x57', last_write(mock, LIS3DHMinimal._REG_CTRL_REG1) == 0x57)

# read(): x = 1.0 g, y = 0.0 g, z = -0.5 g.
x, y, z = accel.read()
check_true('read_x_1g', abs(x - 1.0) < 1e-9)
check_true('read_y_zero_g', abs(y - 0.0) < 1e-9)
check_true('read_z_minus_half_g', abs(z - (-0.5)) < 1e-9)


# --- Register addressing -------------------------------------------------

class RecordingConn(I2CConnectionMock):
    """Mock that records every register read."""

    def __init__(self):
        I2CConnectionMock.__init__(self)
        self.reg_reads = []

    def read_reg(self, reg, length):
        self.reg_reads.append((reg, length))
        return I2CConnectionMock.read_reg(self, reg, length)


rec = RecordingConn()
rec.set_register(LIS3DHMinimal._REG_WHO_AM_I, 0x33)
rec.set_register(LIS3DHMinimal._REG_OUT_X_L | 0x80, 0, 0, 0, 0, 0, 0)
accel_reg = LIS3DHMinimal(rec)
accel_reg.read()
check_true('who_am_i_single_byte_read',
           (LIS3DHMinimal._REG_WHO_AM_I, 1) in rec.reg_reads)
check_true('burst_read_sets_auto_increment_bit',
           (LIS3DHMinimal._REG_OUT_X_L | 0x80, 6) in rec.reg_reads)


# --- Identity check ------------------------------------------------------

bad = I2CConnectionMock()
bad.set_register(LIS3DHMinimal._REG_WHO_AM_I, 0x00)
try:
    LIS3DHMinimal(bad)
    check_true('wrong_who_am_i_raises', False)
except ValueError:
    check_true('wrong_who_am_i_raises', True)


# --- Full driver ---------------------------------------------------------

mock2 = fresh_mock()
full = LIS3DHFull(mock2)
check_true('construct_full', True)

# set_range: FS field of CTRL_REG4 (bits 5:4) — 0x88 (±2) -> 0x98 (±4)
# -> 0xB8 (±16) -> 0x88 (±2).
full.set_range(4)
check_true('set_range_4g', last_write(mock2, LIS3DHMinimal._REG_CTRL_REG4) == 0x98)
full.set_range(16)
check_true('set_range_16g', last_write(mock2, LIS3DHMinimal._REG_CTRL_REG4) == 0xB8)
full.set_range(2)
check_true('set_range_2g', last_write(mock2, LIS3DHMinimal._REG_CTRL_REG4) == 0x88)
try:
    full.set_range(3)
    check_true('set_range_invalid_raises', False)
except ValueError:
    check_true('set_range_invalid_raises', True)

# set_mode: LPen (CTRL_REG1 bit 3) and HR (CTRL_REG4 bit 3).
full.set_mode(1)
check_true('set_mode_normal_clears_lpen',
           (last_write(mock2, LIS3DHMinimal._REG_CTRL_REG1) & 0x08) == 0x00)
check_true('set_mode_normal_clears_hr',
           (last_write(mock2, LIS3DHMinimal._REG_CTRL_REG4) & 0x08) == 0x00)
full.set_mode(2)
check_true('set_mode_hr_sets_hr',
           (last_write(mock2, LIS3DHMinimal._REG_CTRL_REG4) & 0x08) == 0x08)
full.set_mode(0)
check_true('set_mode_lp_sets_lpen',
           (last_write(mock2, LIS3DHMinimal._REG_CTRL_REG1) & 0x08) == 0x08)
full.set_mode(2)
try:
    full.set_mode(3)
    check_true('set_mode_invalid_raises', False)
except ValueError:
    check_true('set_mode_invalid_raises', True)

# set_data_rate: ODR field (bits 7:4). 400 Hz -> code 0x07.
full.set_data_rate(400)
check_true('set_data_rate_400hz',
           (last_write(mock2, LIS3DHMinimal._REG_CTRL_REG1) & 0xF0) == 0x70)
full.set_data_rate(100)
check_true('set_data_rate_100hz',
           (last_write(mock2, LIS3DHMinimal._REG_CTRL_REG1) & 0xF0) == 0x50)
try:
    full.set_data_rate(1620)
    check_true('set_data_rate_lp_only_raises', False)
except ValueError:
    check_true('set_data_rate_lp_only_raises', True)
try:
    full.set_data_rate(123)
    check_true('set_data_rate_invalid_raises', False)
except ValueError:
    check_true('set_data_rate_invalid_raises', True)

# set_axes: Xen/Yen/Zen = CTRL_REG1 bits 2:0.
full.set_axes(False, True, True)
check_true('set_axes_x_off',
           (last_write(mock2, LIS3DHMinimal._REG_CTRL_REG1) & 0x07) == 0x06)
full.set_axes(True, True, True)
check_true('set_axes_all_on',
           (last_write(mock2, LIS3DHMinimal._REG_CTRL_REG1) & 0x07) == 0x07)

# power_down: ODR = 0000, register contents retained.
full.power_down()
check_true('power_down_clears_odr',
           (last_write(mock2, LIS3DHMinimal._REG_CTRL_REG1) & 0xF0) == 0x00)
full.set_data_rate(100)

# read_raw: signed counts at the current resolution (12-bit here).
rx, ry, rz = full.read_raw()
check_true('read_raw_counts', rx == 1000 and ry == 0 and rz == -500)

# new_data_available / read_status.
mock2.set_register(LIS3DHMinimal._REG_STATUS, 0x08)
check_true('new_data_available_set', full.new_data_available())
mock2.set_register(LIS3DHMinimal._REG_STATUS, 0x00)
check_true('new_data_available_clear', not full.new_data_available())
check_true('read_status', full.read_status() == 0x00)

# read_temperature_delta: 8-bit two's complement of OUT_ADC3_H.
mock2.set_register(LIS3DHMinimal._REG_OUT_ADC3_H, 0xFE)  # -2
mock2.set_register(LIS3DHMinimal._REG_TEMP_CFG, 0x00)
check_true('read_temperature_delta_minus2', full.read_temperature_delta() == -2)
check_true('temperature_enables_adc_and_temp',
           last_write(mock2, LIS3DHMinimal._REG_TEMP_CFG) == 0xC0)

# read_adc: 10-bit, derived mapping 1.200 V ± 400 mV over ±512 counts.
mock2.set_register(LIS3DHMinimal._REG_OUT_ADC1_L | 0x80, 0x00, 0x00)
check_true('read_adc_channel_1', abs(full.read_adc(1) - 1.200) < 1e-3)
mock2.set_register(LIS3DHMinimal._REG_OUT_ADC2_L | 0x80, 0x00, 0x40)  # 256 counts
check_true('read_adc_channel_2', abs(full.read_adc(2) - 1.400) < 1e-3)

# enable_adc.
mock2.set_register(LIS3DHMinimal._REG_TEMP_CFG, 0x00)
full.enable_adc(True)
check_true('enable_adc_sets_bit',
           (last_write(mock2, LIS3DHMinimal._REG_TEMP_CFG) & 0x80) == 0x80)
full.enable_adc(False)
check_true('enable_adc_clears_bit',
           (last_write(mock2, LIS3DHMinimal._REG_TEMP_CFG) & 0x80) == 0x00)

# set_high_pass: HPM bits 7:6, HPCF bits 5:4, FDS/HPCLICK/HP_IA2/HP_IA1.
full.set_high_pass(1, 2, True, True, False, True)
check_true('set_high_pass_all', last_write(mock2, LIS3DHMinimal._REG_CTRL_REG2) == 0x6D)
full.set_high_pass(0, 0)
check_true('set_high_pass_defaults', last_write(mock2, LIS3DHMinimal._REG_CTRL_REG2) == 0x00)

# reset_high_pass reads REFERENCE.
mock2.set_register(LIS3DHMinimal._REG_REFERENCE, 0x5A)
check_true('reset_high_pass_reads_reference', full.reset_high_pass() == 0x5A)

# set_reference.
full.set_reference(0x40)
check_true('set_reference', last_write(mock2, LIS3DHMinimal._REG_REFERENCE) == 0x40)

# --- FIFO ---------------------------------------------------------------

# set_fifo: FM bits 7:6, TR bit 5, FTH bits 4:0; FIFO_EN in CTRL_REG5.
full.set_fifo(2, 16)
check_true('set_fifo_stream_mode', last_write(mock2, LIS3DHMinimal._REG_FIFO_CTRL) == 0x90)
check_true('set_fifo_enables_fifo_en',
           (last_write(mock2, LIS3DHMinimal._REG_CTRL_REG5) & 0x40) == 0x40)
full.set_fifo(0)
check_true('set_fifo_bypass', last_write(mock2, LIS3DHMinimal._REG_FIFO_CTRL) == 0x00)
check_true('set_fifo_bypass_clears_fifo_en',
           (last_write(mock2, LIS3DHMinimal._REG_CTRL_REG5) & 0x40) == 0x00)

# fifo_level / read_fifo_status.
mock2.set_register(LIS3DHMinimal._REG_FIFO_SRC, 0x80 | 0x40 | 0x10)
check_true('fifo_level', full.fifo_level() == 0x10)
watermark, overrun, empty, lvl = full.read_fifo_status()
check_true('read_fifo_status', watermark and overrun and not empty and lvl == 0x10)

# read_fifo: one 6-byte burst per sample set, never more than FSS.
mock2.set_register(LIS3DHMinimal._REG_FIFO_SRC, 0x01)
samples = full.read_fifo()
check_true('read_fifo_one_sample', len(samples) == 1)
check_true('read_fifo_values',
           abs(samples[0][0] - 1.0) < 1e-9 and abs(samples[0][2] - (-0.5)) < 1e-9)

# --- Interrupt generators ------------------------------------------------

# set_motion_interrupt: INTx_CFG = AOI | events, INTx_THS, INTx_DURATION.
# At ±2 g the threshold LSB is 16 mg; 0.5 g -> 31 counts. 30 ms at
# 100 Hz -> 3.
full.set_motion_interrupt(1, 0x3F, 0.5, 30, True)
check_true('motion_int_cfg', last_write(mock2, 0x30) == 0xBF)
check_true('motion_int_threshold', last_write(mock2, 0x32) == 31)
check_true('motion_int_duration', last_write(mock2, 0x33) == 3)
full.set_motion_interrupt(2, 0x00, 0.5, 0, False)
check_true('motion_int2_cfg', last_write(mock2, 0x34) == 0x00)

# set_free_fall: INTx_CFG = 0x95 (AOI + all axes low).
full.set_free_fall(1, 0.35, 30)
check_true('free_fall_cfg', last_write(mock2, 0x30) == 0x95)

# set_orientation: 6D movement = AOI|6D = 0x40, position = 0xC0; 4D
# sets D4D_INTx in CTRL_REG5 and clears the Z zones (0x0F).
full.set_orientation(1, False, False)
check_true('orientation_6d_movement', last_write(mock2, 0x30) == 0x7F)
full.set_orientation(2, True, True)
check_true('orientation_6d_position_4d', last_write(mock2, 0x34) == 0xCF)
check_true('orientation_4d_bit',
           (last_write(mock2, LIS3DHMinimal._REG_CTRL_REG5) & 0x01) == 0x01)
full.set_orientation(1, False, False)

# set_latch: LIR_INT1 = CTRL_REG5 bit 3, LIR_INT2 = bit 1.
full.set_latch(1, True)
check_true('latch_int1',
           (last_write(mock2, LIS3DHMinimal._REG_CTRL_REG5) & 0x08) == 0x08)
full.set_latch(1, False)
check_true('unlatch_int1',
           (last_write(mock2, LIS3DHMinimal._REG_CTRL_REG5) & 0x08) == 0x00)

# read_interrupt_source returns INTx_SRC.
mock2.set_register(LIS3DHMinimal._REG_INT1_SRC, 0x40)
check_true('read_interrupt_source', full.read_interrupt_source(1) == 0x40)
mock2.set_register(LIS3DHMinimal._REG_INT2_SRC, 0x20)
check_true('read_interrupt_source2', full.read_interrupt_source(2) == 0x20)

# --- Click detection ----------------------------------------------------

# single = Z (4) -> ZS bit 4; double = X (1) -> XD bit 1;
# threshold 0.5 g at ±2 g -> 31; LIR_Click set.
full.set_click(0, 0, 0.5, 10, 20, 50, True)
check_true('click_cfg_no_axes', last_write(mock2, 0x38) == 0x00)
full.set_click(4, 1, 0.5, 10, 20, 50, True)
check_true('click_cfg_z_single_x_double', last_write(mock2, 0x38) == 0x12)
check_true('click_threshold_latch', last_write(mock2, 0x3A) == 0x9F)
check_true('click_time_limit', last_write(mock2, 0x3B) == 1)
check_true('click_latency', last_write(mock2, 0x3C) == 2)
check_true('click_window', last_write(mock2, 0x3D) == 5)

mock2.set_register(LIS3DHMinimal._REG_CLICK_SRC, 0x40)
check_true('read_click_source', full.read_click_source() == 0x40)

# --- Sleep-to-wake activity ---------------------------------------------

# ACT_THS uses the same scale as INTx_THS; ACT_DUR = (ms*ODR/1000 - 1) / 8.
full.set_activity(0.5, 100)
check_true('activity_threshold', last_write(mock2, 0x3E) == 31)
check_true('activity_duration_rounds_to_one', last_write(mock2, 0x3F) == 1)
full.set_activity(0.5, 801)
check_true('activity_duration_formula', last_write(mock2, 0x3F) == 10)
try:
    full.set_activity(0.001, 100)
    check_true('set_activity_zero_threshold_raises', False)
except ValueError:
    check_true('set_activity_zero_threshold_raises', True)

# set_interrupt_polarity: INT_POLARITY = CTRL_REG6 bit 1.
full.set_interrupt_polarity(True)
check_true('set_interrupt_polarity_active_low',
           (last_write(mock2, LIS3DHMinimal._REG_CTRL_REG6) & 0x02) == 0x02)
full.set_interrupt_polarity(False)
check_true('set_interrupt_polarity_active_high',
           (last_write(mock2, LIS3DHMinimal._REG_CTRL_REG6) & 0x02) == 0x00)

# enable/disable_interrupt routing.
mock2.set_register(LIS3DHMinimal._REG_CTRL_REG3, 0x00)
mock2.set_register(LIS3DHMinimal._REG_CTRL_REG6, 0x00)
full.enable_interrupt(LIS3DHSource.SOURCE_DATA_READY)
check_true('enable_data_ready_int1',
           (last_write(mock2, LIS3DHMinimal._REG_CTRL_REG3) & 0x10) == 0x10)
full.enable_interrupt(LIS3DHSource.SOURCE_IA1, 2)
check_true('enable_ia1_int2',
           (last_write(mock2, LIS3DHMinimal._REG_CTRL_REG6) & 0x40) == 0x40)
full.enable_interrupt(LIS3DHSource.SOURCE_ACTIVITY)
check_true('enable_activity_int2_only',
           (last_write(mock2, LIS3DHMinimal._REG_CTRL_REG6) & 0x08) == 0x08)
full.disable_interrupt(LIS3DHSource.SOURCE_DATA_READY)
check_true('disable_data_ready',
           (last_write(mock2, LIS3DHMinimal._REG_CTRL_REG3) & 0x10) == 0x00)
try:
    full.enable_interrupt(0x1234)
    check_true('enable_unknown_source_raises', False)
except ValueError:
    check_true('enable_unknown_source_raises', True)
try:
    full.enable_interrupt(LIS3DHSource.SOURCE_IA1, 3)
    check_true('enable_invalid_pin_raises', False)
except ValueError:
    check_true('enable_invalid_pin_raises', True)

# poll_interrupt: every enabled source's flag ORed into the status int.
mock2.set_register(LIS3DHMinimal._REG_STATUS, 0x08)
mock2.set_register(LIS3DHMinimal._REG_STATUS_AUX, 0x08)
mock2.set_register(LIS3DHMinimal._REG_FIFO_SRC, 0xC0)
mock2.set_register(LIS3DHMinimal._REG_INT1_SRC, 0x40)
mock2.set_register(LIS3DHMinimal._REG_INT2_SRC, 0x40)
mock2.set_register(LIS3DHMinimal._REG_CLICK_SRC, 0x40)
for src in (LIS3DHSource.SOURCE_DATA_READY, LIS3DHSource.SOURCE_AUX_DATA_READY,
            LIS3DHSource.SOURCE_WATERMARK, LIS3DHSource.SOURCE_OVERRUN,
            LIS3DHSource.SOURCE_IA1, LIS3DHSource.SOURCE_IA2,
            LIS3DHSource.SOURCE_CLICK):
    full.enable_interrupt(src, 1)
check_true('poll_interrupt_all_sources', full.poll_interrupt() == 0x7F)
for src in (LIS3DHSource.SOURCE_DATA_READY, LIS3DHSource.SOURCE_AUX_DATA_READY,
            LIS3DHSource.SOURCE_WATERMARK, LIS3DHSource.SOURCE_OVERRUN,
            LIS3DHSource.SOURCE_IA1, LIS3DHSource.SOURCE_IA2,
            LIS3DHSource.SOURCE_CLICK):
    full.disable_interrupt(src, 1)
full.disable_interrupt(LIS3DHSource.SOURCE_IA1, 2)
full.disable_interrupt(LIS3DHSource.SOURCE_ACTIVITY)
check_true('poll_interrupt_nothing_enabled', full.poll_interrupt() == 0)

# set_sdo_pullup: CTRL_REG0 keeps bit 4 set, toggles bit 7 only.
full.set_sdo_pullup(False)
check_true('set_sdo_pullup_disconnect', last_write(mock2, LIS3DHMinimal._REG_CTRL_REG0) == 0x90)
full.set_sdo_pullup(True)
check_true('set_sdo_pullup_connect', last_write(mock2, LIS3DHMinimal._REG_CTRL_REG0) == 0x10)

# reboot: BOOT bit, then the Initialization Sequence runs again.
mock2.set_register(LIS3DHMinimal._REG_CTRL_REG5, 0x00)
full.reboot()
c5_writes = [w[1] for w in mock2.writes
             if len(w) == 2 and w[0] == LIS3DHMinimal._REG_CTRL_REG5]
check_true('reboot_sets_boot_bit', any(v & 0x80 for v in c5_writes))
check_true('reboot_reruns_init_ctrl4',
           last_write(mock2, LIS3DHMinimal._REG_CTRL_REG4) == 0x88)
check_true('reboot_reruns_init_ctrl1',
           last_write(mock2, LIS3DHMinimal._REG_CTRL_REG1) == 0x57)


# --- Self-test -----------------------------------------------------------

class SelfTestMock(I2CConnectionMock):
    """Loads the post-self-test output data once ST bits are written."""

    def __init__(self):
        I2CConnectionMock.__init__(self)
        self._post = False

    def write(self, data):
        data = bytes(data)
        if len(data) == 2 and data[0] == LIS3DHMinimal._REG_CTRL_REG4 and (data[1] & 0x06):
            self._post = True
            self.set_register(LIS3DHMinimal._REG_OUT_X_L | 0x80,
                              0x80, 0x0C,    # X: 100 -> 200 (delta +100)
                              0x60, 0xEA,    # Y: -200 -> -350 (delta -150)
                              0xC0, 0x12)    # Z: 50 -> 300 (delta +250)
        I2CConnectionMock.write(self, data)


st_mock = SelfTestMock()
st_mock.set_register(LIS3DHMinimal._REG_WHO_AM_I, 0x33)
st_mock.set_register(LIS3DHMinimal._REG_OUT_X_L | 0x80,
                     0x40, 0x06,    # X = 100 counts
                     0x80, 0xF3,    # Y = -200 counts
                     0x20, 0x03)    # Z = 50 counts
st_chip = LIS3DHFull(st_mock)
check_true('self_test_passes', st_chip.self_test() is True)
check_true('self_test_restores_ctrl_reg1',
           last_write(st_mock, LIS3DHMinimal._REG_CTRL_REG1) == 0x57)
check_true('self_test_restores_ctrl_reg4',
           last_write(st_mock, LIS3DHMinimal._REG_CTRL_REG4) == 0x88)
try:
    st_chip.self_test(mode=3)
    check_true('self_test_invalid_mode_raises', False)
except ValueError:
    check_true('self_test_invalid_mode_raises', True)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
