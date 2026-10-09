"""Unit test for the BMA150 — runs without hardware using the I2C mock.

Verifies the driver's register read/write sequencing, sign-extension and
range-scaling math, and the various interrupt / threshold paths. Mock
the BMA150's CHIP_ID (0x00 masked with 0x07 == 0x02) so init's identity
check passes.
"""

import sys
from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.accelerometer.bma150 import BMA150Minimal, BMA150Full

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


# Preload CHIP_ID = 0x02 so init's identity check passes (mask 0x07 = 0x02).
# Preload RANGE_BW = 0x00 (calibration bits 7:5 = 0; range/bw bits = 0).
# Preload a known 6-byte burst at 0x02: x=0x80,0x00 (raw=0x200 -> +512
# -> -512 after sign-extension, i.e. -2 g at ±2 g range); y=0x00,0x00
# (raw=0 -> 0 g); z=0x40,0x00 (raw=0x100 -> +256 -> 1 g).
# LSB byte format is 8'b<new_data><acc[1:0]><don't_care><acc[5:2]>, MSB is
# <acc[9:2]>. To get raw = 0x200 (X), MSB = 0x80 (bits 9:2 = 0b1000_0000 = 0x80),
# LSB = 0x00 (bits 7:6 = 0, bits 5:2 don't care, bit 0 = new_data=0).
mock = I2CConnectionMock()
mock.set_register(BMA150Minimal._REG_CHIP_ID, 0x02)
mock.set_register(BMA150Minimal._REG_RANGE_BW, 0x00)
mock.set_register(BMA150Minimal._REG_ACC_X_LSB, 0x00, 0x80)  # raw x = 0x200
mock.set_register(BMA150Minimal._REG_ACC_Y_LSB, 0x00, 0x00)  # raw y = 0
mock.set_register(BMA150Minimal._REG_ACC_Z_LSB, 0x00, 0x40)  # raw z = 0x100

accel = BMA150Minimal(mock)
check_true('construct_minimal', True)

# Init reads CHIP_ID then writes RANGE_BW = (0x00 & 0xE0) | 0x00 | 0x02 = 0x02.
rb_writes = [w for w in mock.writes if len(w) == 2 and w[0] == BMA150Minimal._REG_RANGE_BW]
check_true('init_writes_range_bw_0x02', rb_writes and rb_writes[0][1] == 0x02)

# read(): raw_x=0x200 -> signed -512 -> -2 g; raw_y=0 -> 0 g; raw_z=0x100 -> 1 g.
x, y, z = accel.read()
check_true('read_x_minus_2g', abs(x - (-2.0)) < 1e-9)
check_true('read_y_zero_g', abs(y - 0.0) < 1e-9)
check_true('read_z_plus_1g', abs(z - 1.0) < 1e-9)


# --- Full driver ---------------------------------------------------------

# A fresh mock for the Full driver (so we can capture a clean write log).
mock2 = I2CConnectionMock()
mock2.set_register(BMA150Minimal._REG_CHIP_ID, 0x02)
mock2.set_register(BMA150Minimal._REG_RANGE_BW, 0x00)
mock2.set_register(BMA150Minimal._REG_ACC_X_LSB, 0x00, 0x80)
mock2.set_register(BMA150Minimal._REG_ACC_Y_LSB, 0x00, 0x00)
mock2.set_register(BMA150Minimal._REG_ACC_Z_LSB, 0x00, 0x40)

accel_full = BMA150Full(mock2)
check_true('construct_full', True)

# set_range(4): RANGE_BW = (0x00 & 0xE0) | 0x08 | (0x02 & 0x07) = 0x0A.
accel_full.set_range(4)
rb_writes = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA150Minimal._REG_RANGE_BW]
check_true('set_range_4g', rb_writes and rb_writes[-1][1] == 0x0A)

# set_bandwidth(190): RANGE_BW = (0x0A & 0xF8) | 0x03 = 0x0B.
accel_full.set_bandwidth(190)
rb_writes = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA150Minimal._REG_RANGE_BW]
check_true('set_bandwidth_190hz', rb_writes and rb_writes[-1][1] == 0x0B)

# read_raw(): same data as the read() test, signed 10-bit.
x, y, z = accel_full.read_raw()
check_true('read_raw_x', x == -512)
check_true('read_raw_y', y == 0)
check_true('read_raw_z', z == 256)

# set_low_g(0.4, 40): with range=4 the formula is round(0.4 * 255 / 4) = 26.
#   LG_DUR = 40; LG_hyst = 0; counter_LG = 0; INT_CTRL |= 0x01.
mock2.set_register(BMA150Minimal._REG_HYST_DUR, 0x00)
mock2.set_register(BMA150Minimal._REG_INT_CTRL, 0x00)
accel_full.set_low_g(0.4, 40)
thres = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA150Minimal._REG_LG_THRES]
dur = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA150Minimal._REG_LG_DUR]
ic = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA150Minimal._REG_INT_CTRL]
check_true('set_low_g_threshold', thres and thres[-1][1] == 26)
check_true('set_low_g_duration', dur and dur[-1][1] == 40)
check_true('set_low_g_enables_int', ic and (ic[-1][1] & 0x01))

# set_high_g(4.0, 2): HG_THRES = round(4.0/2 * 255) = 510 -> clamped to 255;
#   HG_DUR = 2; HG_hyst = 0; counter_HG = 0; INT_CTRL |= 0x02.
mock2.set_register(BMA150Minimal._REG_INT_CTRL, 0x00)
accel_full.set_high_g(4.0, 2)
thres = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA150Minimal._REG_HG_THRES]
dur = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA150Minimal._REG_HG_DUR]
ic = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA150Minimal._REG_INT_CTRL]
check_true('set_high_g_threshold_clamped', thres and thres[-1][1] == 255)
check_true('set_high_g_duration', dur and dur[-1][1] == 2)
check_true('set_high_g_enables_int', ic and (ic[-1][1] & 0x02))

# set_any_motion(0.5, samples=3): any_motion_thres with range=4 ->
#   scale = 128/256 = 0.5, code = round(0.5 / (0.0156 * 0.5)) = 64.
#   HYST_DUR bits 7:6 = 0x40; CONFIG |= 0x40 (enable_adv_INT); INT_CTRL |= 0x40.
accel_full.set_any_motion(0.5, samples=3)
am = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA150Minimal._REG_ANY_MOTION_THRES]
hd = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA150Minimal._REG_HYST_DUR]
cfg = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA150Minimal._REG_CONFIG]
ic = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA150Minimal._REG_INT_CTRL]
check_true('set_any_motion_threshold', am and am[-1][1] == 64)
check_true('set_any_motion_dur_3samples', hd and (hd[-1][1] & 0xC0) == 0x40)
check_true('set_any_motion_enables_adv_int', cfg and (cfg[-1][1] & 0x40))
check_true('set_any_motion_enables_int', ic and (ic[-1][1] & 0x40))

# set_latch(True): CONFIG |= 0x10.
accel_full.set_latch(True)
cfg = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA150Minimal._REG_CONFIG]
check_true('set_latch_true', cfg and (cfg[-1][1] & 0x10))

# set_latch(False): CONFIG &= ~0x10.
accel_full.set_latch(False)
cfg = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA150Minimal._REG_CONFIG]
check_true('set_latch_false', cfg and not (cfg[-1][1] & 0x10))

# clear_interrupt(): reads CTRL, writes CTRL | 0x40.
mock2.set_register(BMA150Minimal._REG_CTRL, 0x00)
accel_full.clear_interrupt()
ctrl = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA150Minimal._REG_CTRL]
check_true('clear_interrupt_writes_reset', ctrl and (ctrl[-1][1] & 0x40))

# soft_reset(): CTRL |= 0x02, 30 ms wait, then RANGE_BW restored.
# After set_range(4), the driver was operating at range=4 (RANGE_BW bits 4:3 = 0x08).
mock2.set_register(BMA150Minimal._REG_CTRL, 0x00)
mock2.set_register(BMA150Minimal._REG_RANGE_BW, 0x00)
accel_full.soft_reset()
ctrl = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA150Minimal._REG_CTRL]
rb = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA150Minimal._REG_RANGE_BW]
check_true('soft_reset_writes_soft_reset_bit', ctrl and (ctrl[-1][1] & 0x02))
# After soft_reset, range (4) + bw 100 Hz -> 0x08 | 0x02 = 0x0A.
last_rb = [r for r in rb if r[0] == BMA150Minimal._REG_RANGE_BW]
check_true('soft_reset_restores_range_bw', last_rb and last_rb[-1][1] == 0x0A)

# set_wake_up(True, 80): CONFIG = (CONFIG & 0xF9) | 0x02 | 0x01.
mock2.set_register(BMA150Minimal._REG_CONFIG, 0x00)
accel_full.set_wake_up(True, 80)
cfg = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA150Minimal._REG_CONFIG]
check_true('set_wake_up_80ms', cfg and (cfg[-1][1] & 0x03) == 0x03 and (cfg[-1][1] & 0x06) == 0x02)

# set_wake_up(False): CONFIG &= ~0x01; pause bits unchanged.
accel_full.set_wake_up(False)
cfg = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA150Minimal._REG_CONFIG]
check_true('set_wake_up_false', cfg and not (cfg[-1][1] & 0x01))

# sleep() / wake(): sleep writes CTRL | 0x01; wake writes CTRL & ~0x01.
mock2.set_register(BMA150Minimal._REG_CTRL, 0x00)
accel_full.sleep()
ctrl = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA150Minimal._REG_CTRL]
check_true('sleep_writes_sleep_bit', ctrl and (ctrl[-1][1] & 0x01))
accel_full.wake()
ctrl = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA150Minimal._REG_CTRL]
check_true('wake_clears_sleep_bit', ctrl and not (ctrl[-1][1] & 0x01))

# self_test(): writes CTRL | 0x04, reads STATUS, restores CTRL.
mock2.set_register(BMA150Minimal._REG_CTRL, 0x00)
mock2.set_register(BMA150Minimal._REG_STATUS, 0x80)  # st_result bit set
st = accel_full.self_test()
ctrl = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA150Minimal._REG_CTRL]
check_true('self_test_returns_true_on_st_result', st is True)
check_true('self_test_restores_ctrl', ctrl[-1][1] == 0x00)

# read_version(): VERSION 0xAB -> (0xA, 0xB).
mock2.set_register(BMA150Minimal._REG_VERSION, 0xAB)
al, ml = accel_full.read_version()
check_true('read_version_al', al == 0xA)
check_true('read_version_ml', ml == 0xB)

# read_temperature(): raw = 0x40 -> 64 * 0.5 - 30 = 2.
mock2.set_register(BMA150Minimal._REG_TEMP, 0x40)
temp = accel_full.read_temperature()
check_true('read_temperature', abs(temp - 2.0) < 1e-9)

# read_customer / write_customer.
mock2.set_register(BMA150Minimal._REG_CUSTOMER_1, 0xA5)
check_true('read_customer_0', accel_full.read_customer(0) == 0xA5)
accel_full.write_customer(1, 0x5A)
cust = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA150Minimal._REG_CUSTOMER_2]
check_true('write_customer_1', cust and cust[-1][1] == 0x5A)

# set_shadow(True) and (False) toggle CONFIG bit 3.
mock2.set_register(BMA150Minimal._REG_CONFIG, 0x00)
accel_full.set_shadow(True)
cfg = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA150Minimal._REG_CONFIG]
check_true('set_shadow_true', cfg and (cfg[-1][1] & 0x08))
accel_full.set_shadow(False)
cfg = [w for w in mock2.writes if len(w) == 2 and w[0] == BMA150Minimal._REG_CONFIG]
check_true('set_shadow_false', cfg and not (cfg[-1][1] & 0x08))

# Register API usage: the driver uses read_reg/write_reg (the
# RegisterConnection interface), and the burst read of 0x02..0x07 is
# delivered as a single 6-byte read.
class RecordingConn(I2CConnectionMock):
    def __init__(self):
        super().__init__()
        self.reg_reads = []

    def read_reg(self, reg, length):
        self.reg_reads.append((reg, length))
        return super().read_reg(reg, length)


mock3 = RecordingConn()
mock3.set_register(BMA150Minimal._REG_CHIP_ID, 0x02)
mock3.set_register(BMA150Minimal._REG_RANGE_BW, 0x00)
mock3.set_register(BMA150Minimal._REG_ACC_X_LSB, 0x00, 0x80)
mock3.set_register(BMA150Minimal._REG_ACC_Y_LSB, 0x00, 0x00)
mock3.set_register(BMA150Minimal._REG_ACC_Z_LSB, 0x00, 0x40)
accel_reg = BMA150Minimal(mock3)
accel_reg.read()
check_true('reg_burst_read_6_bytes',
           (BMA150Minimal._REG_ACC_X_LSB, 6) in mock3.reg_reads)
check_true('reg_chip_id_single_byte_read',
           (BMA150Minimal._REG_CHIP_ID, 1) in mock3.reg_reads)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
