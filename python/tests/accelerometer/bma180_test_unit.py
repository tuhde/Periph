"""Unit test for the BMA180 — runs without hardware using the I2C mock.

Verifies the driver's register read/write sequencing, 14-bit sign-extension
and range-scaling math, and the various configuration paths.
"""

import sys
from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.accelerometer.bma180 import BMA180Minimal, BMA180Full

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


# 14-bit two's complement encoding: per axis, raw = (MSB << 6) | (LSB >> 2).
# To get raw = +0x200 (X), MSB = 0x08 (bits 13:6 = 0b0000_1000), LSB = 0x00.
# To get raw = -0x200 (Y), MSB = ((-512 + 16384) >> 6) = (15872 >> 6) = 248 = 0xF8, LSB = 0x00.
# To get raw = 0 (Z), MSB = 0x00, LSB = 0x00.

# Preload CHIP_ID = 0x03 (mask 0x07 = 0x03) so init's identity check passes.
# Preload CTRL_REG0 = 0x00, OFFSET_LSB1 = 0x00, BW_TCS = 0x00.
mock = I2CConnectionMock()
mock.set_register(BMA180Minimal._REG_CHIP_ID, 0x03)
mock.set_register(BMA180Minimal._REG_CTRL_REG0, 0x00)
mock.set_register(BMA180Minimal._REG_OFFSET_LSB1, 0x00)
mock.set_register(BMA180Minimal._REG_BW_TCS, 0x00)
mock.set_register(BMA180Minimal._REG_ACC_X_LSB, 0x00, 0x08)  # raw x = 0x200 -> 512
mock.set_register(BMA180Minimal._REG_ACC_Y_LSB, 0x00, 0xF8)  # raw y = -512
mock.set_register(BMA180Minimal._REG_ACC_Z_LSB, 0x00, 0x00)  # raw z = 0

accel = BMA180Minimal(mock)
check_true('construct_minimal', True)

# Init writes:
#   CTRL_REG0 |= 0x10 (ee_w)
#   OFFSET_LSB1 = (0x00 & 0xF1) | 0x04 = 0x04 (range = ±2 g, bits 3:1)
#   BW_TCS = (0x00 & 0x0F) | 0x40 = 0x40 (bw = 150 Hz, bits 7:4)
ctrl0_writes = [w for w in mock.writes if len(w) == 2 and w[0] == BMA180Minimal._REG_CTRL_REG0]
olsb1_writes = [w for w in mock.writes if len(w) == 2 and w[0] == BMA180Minimal._REG_OFFSET_LSB1]
bw_writes = [w for w in mock.writes if len(w) == 2 and w[0] == BMA180Minimal._REG_BW_TCS]
check_true('init_sets_ee_w', ctrl0_writes and (ctrl0_writes[0][1] & 0x10))
check_true('init_sets_range_2g', olsb1_writes and olsb1_writes[0][1] == 0x04)
check_true('init_sets_bw_150hz', bw_writes and bw_writes[0][1] == 0x40)

# read(): raw_x = 0x200 -> +512 -> 512/4096 = 0.125 g; raw_y = -512 -> -0.125 g; raw_z = 0 -> 0 g.
x, y, z = accel.read()
check_true('read_x_plus_0_125g', abs(x - 0.125) < 1e-9)
check_true('read_y_minus_0_125g', abs(y - (-0.125)) < 1e-9)
check_true('read_z_zero_g', abs(z - 0.0) < 1e-9)


# --- Full driver ---------------------------------------------------------

# Fresh mock so the write log is clean.
mock2 = I2CConnectionMock()
mock2.set_register(BMA180Minimal._REG_CHIP_ID, 0x03)
mock2.set_register(BMA180Minimal._REG_CTRL_REG0, 0x00)
mock2.set_register(BMA180Minimal._REG_OFFSET_LSB1, 0x00)
mock2.set_register(BMA180Minimal._REG_BW_TCS, 0x00)
mock2.set_register(BMA180Minimal._REG_ACC_X_LSB, 0x00, 0x08)
mock2.set_register(BMA180Minimal._REG_ACC_Y_LSB, 0x00, 0x00)
# raw z = 0x100 = 256 -> MSB = 0x04, LSB = 0x00.
mock2.set_register(BMA180Minimal._REG_ACC_Z_LSB, 0x00, 0x04)

accel_full = BMA180Full(mock2)
check_true('construct_full', True)

# set_range(8): OFFSET_LSB1 = (0x04 & ~0x0E) | 0x0A = 0x0A.
accel_full.set_range(8)
olsb1_writes = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA180Minimal._REG_OFFSET_LSB1]
check_true('set_range_8g', olsb1_writes and olsb1_writes[-1][1] == 0x0A)

# set_bandwidth(40): BW_TCS = (0x40 & 0x0F) | 0x20 = 0x20.
accel_full.set_bandwidth(40)
bw_writes = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA180Minimal._REG_BW_TCS]
check_true('set_bandwidth_40hz', bw_writes and bw_writes[-1][1] == 0x20)

# set_filter_mode(1): BW_TCS = (0x20 & 0x0F) | 0x80 = 0x80.
accel_full.set_filter_mode(1)
bw_writes = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA180Minimal._REG_BW_TCS]
check_true('set_filter_mode_high_pass', bw_writes and bw_writes[-1][1] == 0x80)

# set_mode(2): TCO_Z = (0x00 & ~0x03) | 0x02 = 0x02.
mock2.set_register(BMA180Minimal._REG_TCO_Z, 0x00)
accel_full.set_mode(2)
tcoz_writes = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA180Minimal._REG_TCO_Z]
check_true('set_mode_2', tcoz_writes and tcoz_writes[-1][1] == 0x02)

# set_resolution(12): OFFSET_T |= 0x01.
mock2.set_register(BMA180Minimal._REG_OFFSET_T, 0x00)
accel_full.set_resolution(12)
ot_writes = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA180Minimal._REG_OFFSET_T]
check_true('set_resolution_12bit', ot_writes and (ot_writes[-1][1] & 0x01))

# set_resolution(14): OFFSET_T &= ~0x01.
accel_full.set_resolution(14)
ot_writes = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA180Minimal._REG_OFFSET_T]
check_true('set_resolution_14bit', ot_writes and not (ot_writes[-1][1] & 0x01))

# read_raw(): same data as the read() test, signed 14-bit.
mock2.set_register(BMA180Minimal._REG_ACC_X_LSB, 0x00, 0x08)
mock2.set_register(BMA180Minimal._REG_ACC_Y_LSB, 0x00, 0x00)
mock2.set_register(BMA180Minimal._REG_ACC_Z_LSB, 0x00, 0x04)
x, y, z = accel_full.read_raw()
check_true('read_raw_x', x == 512)
check_true('read_raw_y', y == 0)
check_true('read_raw_z', z == 256)

# read_temperature(): raw = 0x02 -> 25.0 + (2 - 2) * 0.5 = 25.0
#                    raw = 0x82 (-126 as int8) -> 25.0 + (-126 - 2) * 0.5 = -39.0
mock2.set_register(BMA180Minimal._REG_TEMP, 0x02)
check_true('read_temperature_25C', abs(accel_full.read_temperature() - 25.0) < 1e-9)
mock2.set_register(BMA180Minimal._REG_TEMP, 0x82)
check_true('read_temperature_neg', abs(accel_full.read_temperature() - (-39.0)) < 1e-9)

# new_data_available: bits 0 of the three LSB registers.
mock2.set_register(BMA180Minimal._REG_ACC_X_LSB, 0x01)
mock2.set_register(BMA180Minimal._REG_ACC_Y_LSB, 0x01)
mock2.set_register(BMA180Minimal._REG_ACC_Z_LSB, 0x01)
check_true('new_data_available_true', accel_full.new_data_available() is True)
mock2.set_register(BMA180Minimal._REG_ACC_X_LSB, 0x00)
check_true('new_data_available_false', accel_full.new_data_available() is False)

# set_shadow(False): GAIN_Y |= 0x01.
mock2.set_register(BMA180Minimal._REG_GAIN_Y, 0x00)
accel_full.set_shadow(False)
gy_writes = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA180Minimal._REG_GAIN_Y]
check_true('set_shadow_false_sets_bit', gy_writes and (gy_writes[-1][1] & 0x01))
accel_full.set_shadow(True)
gy_writes = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA180Minimal._REG_GAIN_Y]
check_true('set_shadow_true_clears_bit', gy_writes and not (gy_writes[-1][1] & 0x01))

# set_sample_skip(True): OFFSET_LSB1 |= 0x01.
mock2.set_register(BMA180Minimal._REG_OFFSET_LSB1, 0x00)
accel_full.set_sample_skip(True)
olsb1_writes = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA180Minimal._REG_OFFSET_LSB1]
check_true('set_sample_skip_true', olsb1_writes and (olsb1_writes[-1][1] & 0x01))
accel_full.set_sample_skip(False)
olsb1_writes = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA180Minimal._REG_OFFSET_LSB1]
check_true('set_sample_skip_false', olsb1_writes and not (olsb1_writes[-1][1] & 0x01))

# set_low_g: writes LOW_TH, LOW_DUR (preserving bit 0), HIGH_LOW_INFO axes.
# range_g currently 8 (set above) -> code = round(0.3 / 8 * 255) = 10.
# LOW_DUR code = round(40 / 2.085) = 19, dur byte = (19 << 1) | bit0 = 0x26 or 0x27.
mock2.set_register(BMA180Minimal._REG_HIGH_LOW_INFO, 0x00)
mock2.set_register(BMA180Minimal._REG_LOW_DUR, 0x01)  # bit 0 = tco_range calibration
accel_full.set_low_g(0.3, 40, hysteresis_g=0.05, axes=0x07, counter=0, filtered=True)
lt_writes = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA180Minimal._REG_LOW_TH]
ld_writes = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA180Minimal._REG_LOW_DUR]
hli_writes = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA180Minimal._REG_HIGH_LOW_INFO]
check_true('set_low_g_threshold', lt_writes and lt_writes[-1][1] == 10)
check_true('set_low_g_dur_preserves_bit0', ld_writes and (ld_writes[-1][1] & 0x01) == 0x01)
# axes = 0x07, low axes bits = bits 3:1.
check_true('set_low_g_axes', hli_writes and (hli_writes[-1][1] & 0x0E) == 0x0E)
check_true('set_low_g_low_filt_bit', hli_writes and (hli_writes[-1][1] & 0x01))

# set_high_g: same shape, HG writes to HIGH_TH, HIGH_DUR, HIGH_LOW_INFO bits 7:5.
mock2.set_register(BMA180Minimal._REG_HIGH_LOW_INFO, 0x00)
mock2.set_register(BMA180Minimal._REG_HIGH_DUR, 0x00)
accel_full.set_high_g(1.8, 20, axes=0x07)
ht_writes = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA180Minimal._REG_HIGH_TH]
hd_writes = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA180Minimal._REG_HIGH_DUR]
hli_writes = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA180Minimal._REG_HIGH_LOW_INFO]
# code = round(1.8 / 8 * 255) = 57.
check_true('set_high_g_threshold', ht_writes and ht_writes[-1][1] == 57)
# axes = 0x07, high axes bits = bits 7:5.
check_true('set_high_g_axes', hli_writes and (hli_writes[-1][1] & 0xE0) == 0xE0)
check_true('set_high_g_high_filt_bit', hli_writes and (hli_writes[-1][1] & 0x10))

# set_slope: writes SLOPE_TH, TCO_X, SLOPE_TAPSENS axes, CTRL_REG3.
mock2.set_register(BMA180Minimal._REG_TCO_X, 0x00)
mock2.set_register(BMA180Minimal._REG_SLOPE_TAPSENS, 0x00)
mock2.set_register(BMA180Minimal._REG_CTRL_REG3, 0x00)
accel_full.set_slope(0.3, 3, 0x07, True)
st_writes = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA180Minimal._REG_SLOPE_TH]
tcox_writes = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA180Minimal._REG_TCO_X]
st_info_writes = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA180Minimal._REG_SLOPE_TAPSENS]
cr3_writes = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA180Minimal._REG_CTRL_REG3]
# slope code = round(0.3 / (0.0156 * 8 / 2)) = round(4.81) = 5.
check_true('set_slope_threshold', st_writes and st_writes[-1][1] == 5)
# samples=3 -> code 0x01 in TCO_X bits 1:0.
check_true('set_slope_dur_3', tcox_writes and (tcox_writes[-1][1] & 0x03) == 0x01)
# axes = 0x07 in slope bits 7:5.
check_true('set_slope_axes', st_info_writes and (st_info_writes[-1][1] & 0xE0) == 0xE0)
# CTRL_REG3: slope_int (0x40) + adv_int (0x04) set; slope_alert (0x80) cleared.
check_true('set_slope_ctrl_reg3', cr3_writes and (cr3_writes[-1][1] & 0x44) == 0x44 and not (cr3_writes[-1][1] & 0x80))

# set_alert(True): CTRL_REG3 has slope_alert + adv_int set; slope_int cleared.
mock2.set_register(BMA180Minimal._REG_CTRL_REG3, 0x00)
accel_full.set_alert(True)
cr3_writes = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA180Minimal._REG_CTRL_REG3]
check_true('set_alert_ctrl_reg3', cr3_writes and (cr3_writes[-1][1] & 0x84) == 0x84 and not (cr3_writes[-1][1] & 0x40))

# set_tap: writes TAPSENS_TH, GAIN_T bits 2:0.
mock2.set_register(BMA180Minimal._REG_GAIN_T, 0x00)
accel_full.set_tap(0.5, 250, 0x07, True)
gt_writes = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA180Minimal._REG_GAIN_T]
# window_ms = 250 -> code 0x04.
check_true('set_tap_dur_250ms', gt_writes and (gt_writes[-1][1] & 0x07) == 0x04)

# set_latch(True): CTRL_REG3 |= 0x01.
mock2.set_register(BMA180Minimal._REG_CTRL_REG3, 0x00)
accel_full.set_latch(True)
cr3_writes = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA180Minimal._REG_CTRL_REG3]
check_true('set_latch_true', cr3_writes and (cr3_writes[-1][1] & 0x01))

# clear_interrupt(): CTRL_REG0 |= 0x40.
mock2.set_register(BMA180Minimal._REG_CTRL_REG0, 0x00)
accel_full.clear_interrupt()
ctrl0_writes = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA180Minimal._REG_CTRL_REG0]
check_true('clear_interrupt_sets_reset_int', ctrl0_writes and (ctrl0_writes[-1][1] & 0x40))

# set_wake_up(True, 80): TCO_Y bits 1:0 = 0x01; GAIN_Z bit 0 = 1.
mock2.set_register(BMA180Minimal._REG_TCO_Y, 0x00)
mock2.set_register(BMA180Minimal._REG_GAIN_Z, 0x00)
accel_full.set_wake_up(True, 80)
tcoy_writes = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA180Minimal._REG_TCO_Y]
gz_writes = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA180Minimal._REG_GAIN_Z]
check_true('set_wake_up_80ms_dur', tcoy_writes and (tcoy_writes[-1][1] & 0x03) == 0x01)
check_true('set_wake_up_sets_bit', gz_writes and (gz_writes[-1][1] & 0x01))

# sleep()/wake(): CTRL_REG0 |= 0x02 / &= ~0x02.
mock2.set_register(BMA180Minimal._REG_CTRL_REG0, 0x00)
accel_full.sleep()
ctrl0_writes = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA180Minimal._REG_CTRL_REG0]
check_true('sleep_sets_bit', ctrl0_writes and (ctrl0_writes[-1][1] & 0x02))
accel_full.wake()
ctrl0_writes = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA180Minimal._REG_CTRL_REG0]
check_true('wake_clears_bit', ctrl0_writes and not (ctrl0_writes[-1][1] & 0x02))

# soft_reset(): RESET register written with 0xB6.
mock2.set_register(BMA180Minimal._REG_CHIP_ID, 0x03)
accel_full.soft_reset()
reset_writes = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA180Minimal._REG_RESET]
check_true('soft_reset_writes_0xB6', reset_writes and reset_writes[0][1] == 0xB6)

# read_version: VERSION = 0xAB -> (0xA, 0xB).
mock2.set_register(BMA180Minimal._REG_VERSION, 0xAB)
al, ml = accel_full.read_version()
check_true('read_version_al', al == 0xA)
check_true('read_version_ml', ml == 0xB)

# read_customer / write_customer.
mock2.set_register(BMA180Minimal._REG_CD1, 0xA5)
check_true('read_customer_0', accel_full.read_customer(0) == 0xA5)
accel_full.write_customer(1, 0x5A)
cd_writes = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA180Minimal._REG_CD2]
check_true('write_customer_1', cd_writes and cd_writes[-1][1] == 0x5A)

# read_status: STATUS_REG1..4.
mock2.set_register(BMA180Minimal._REG_STATUS_REG1, 0x01)
mock2.set_register(BMA180Minimal._REG_STATUS_REG2, 0x02)
mock2.set_register(BMA180Minimal._REG_STATUS_REG3, 0x80)
mock2.set_register(BMA180Minimal._REG_STATUS_REG4, 0x40)
s1, s2, s3, s4 = accel_full.read_status()
check_true('read_status_s1', s1 == 0x01)
check_true('read_status_s2', s2 == 0x02)
check_true('read_status_s3', s3 == 0x80)
check_true('read_status_s4', s4 == 0x40)

# poll_interrupt: STATUS_REG3 byte.
check_true('poll_interrupt', accel_full.poll_interrupt() == 0x80)

# read_sign: STATUS_REG2 bits 2..0 = low signs; STATUS_REG4 bits 7..5 = high signs; bits 4..2 = tapsens.
mock2.set_register(BMA180Minimal._REG_STATUS_REG2, 0x07)
mock2.set_register(BMA180Minimal._REG_STATUS_REG4, 0xFF)
sign = accel_full.read_sign()
check_true('read_sign_low_neg', sign['low'] == (-1, -1, -1))
check_true('read_sign_high_neg', sign['high'] == (-1, -1, -1))
check_true('read_sign_tapsens_neg', sign['tapsens'] == (-1, -1, -1))

# Register API usage: burst read of 0x02..0x07.
class RecordingConn(I2CConnectionMock):
    def __init__(self):
        super().__init__()
        self.reg_reads = []

    def read_reg(self, reg, length):
        self.reg_reads.append((reg, length))
        return super().read_reg(reg, length)


mock3 = RecordingConn()
mock3.set_register(BMA180Minimal._REG_CHIP_ID, 0x03)
mock3.set_register(BMA180Minimal._REG_CTRL_REG0, 0x00)
mock3.set_register(BMA180Minimal._REG_OFFSET_LSB1, 0x00)
mock3.set_register(BMA180Minimal._REG_BW_TCS, 0x00)
mock3.set_register(BMA180Minimal._REG_ACC_X_LSB, 0x00, 0x08)
accel_reg = BMA180Minimal(mock3)
accel_reg.read()
check_true('reg_burst_read_6_bytes',
           (BMA180Minimal._REG_ACC_X_LSB, 6) in mock3.reg_reads)
check_true('reg_chip_id_single_byte_read',
           (BMA180Minimal._REG_CHIP_ID, 1) in mock3.reg_reads)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)