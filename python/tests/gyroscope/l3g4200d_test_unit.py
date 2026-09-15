import sys

import periph.chips.gyroscope.l3g4200d as _drv
from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.gyroscope.l3g4200d import L3G4200DFull, L3G4200DMinimal

REG_WHO_AM_I = _drv._REG_WHO_AM_I
REG_CTRL_REG1 = _drv._REG_CTRL_REG1
REG_CTRL_REG4 = _drv._REG_CTRL_REG4
REG_STATUS = _drv._REG_STATUS
REG_OUT_TEMP = _drv._REG_OUT_TEMP
REG_OUT_X_L = _drv._REG_OUT_X_L
REG_OUT_X_H = _drv._REG_OUT_X_H
REG_OUT_Y_L = _drv._REG_OUT_Y_L
REG_OUT_Y_H = _drv._REG_OUT_Y_H
REG_OUT_Z_L = _drv._REG_OUT_Z_L
REG_OUT_Z_H = _drv._REG_OUT_Z_H
REG_FIFO_CTRL = _drv._REG_FIFO_CTRL
REG_FIFO_SRC = _drv._REG_FIFO_SRC
REG_INT1_CFG = _drv._REG_INT1_CFG
REG_INT1_THS_XH = _drv._REG_INT1_THS_XH
REG_INT1_THS_XL = _drv._REG_INT1_THS_XL
REG_INT1_THS_YH = _drv._REG_INT1_THS_YH
REG_INT1_THS_YL = _drv._REG_INT1_THS_YL
REG_INT1_THS_ZH = _drv._REG_INT1_THS_ZH
REG_INT1_THS_ZL = _drv._REG_INT1_THS_ZL
REG_INT1_DURATION = _drv._REG_INT1_DURATION
REG_CTRL_REG2 = _drv._REG_CTRL_REG2
REG_CTRL_REG3 = _drv._REG_CTRL_REG3
REG_CTRL_REG5 = _drv._REG_CTRL_REG5

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


connection = I2CConnectionMock()
connection.set_register(REG_WHO_AM_I, 0xD3)
gyro = L3G4200DMinimal(connection)
check_true('init', True)

# Default CTRL_REG1 write should be 0x0F (PD=1, all axes enabled)
ctrl1_writes = [w for w in connection.writes if len(w) == 2 and w[0] == REG_CTRL_REG1]
check_true('init_ctrl1_default', ctrl1_writes[-1][1] == 0x0F)
# Default CTRL_REG4 write should be 0x80 (BDU=1, ±250 dps, 4-wire)
ctrl4_writes = [w for w in connection.writes if len(w) == 2 and w[0] == REG_CTRL_REG4]
check_true('init_ctrl4_default', ctrl4_writes[-1][1] == 0x80)

# Verify angular_rate conversion. Little-endian: low byte first, then high byte.
# Bytes [0x10, 0x00] → value 0x0010 = 16 → 16 * 0.00875 dps → rad/s.
#
# For I²C burst reads the L3G4200D driver sends the sub-address with bit 7
# set (auto-increment flag). The mock's write_read stores bytes at the raw
# sub-address, so preload at (reg | 0x80) to match.
connection.set_register(REG_OUT_X_L | 0x80, 0x10, 0x00)   # X = +16 (LE: low=0x10, high=0x00)
connection.set_register(REG_OUT_Y_L | 0x80, 0x00, 0x00)   # Y = 0
connection.set_register(REG_OUT_Z_L | 0x80, 0xF0, 0xFF)   # Z = -16 (LE: low=0xF0, high=0xFF → 0xFFF0)
x, y, z = gyro.angular_rate()
expected_x = 16 * 0.00875 * (3.141592653589793 / 180.0)
expected_z = -16 * 0.00875 * (3.141592653589793 / 180.0)
check_true('angular_rate_x', abs(x - expected_x) < 1e-9)
check_true('angular_rate_y', y == 0.0)
check_true('angular_rate_z', abs(z - expected_z) < 1e-9)

# Full driver
gyro_full = L3G4200DFull(connection)
check_true('full_init', True)

# who_am_i
check_true('who_am_i', gyro_full.who_am_i() == 0xD3)

# configure()
gyro_full.configure(odr=200, bandwidth=0, full_scale=500)
ctrl1_writes = [w for w in connection.writes if len(w) == 2 and w[0] == REG_CTRL_REG1]
ctrl4_writes = [w for w in connection.writes if len(w) == 2 and w[0] == REG_CTRL_REG4]
check_true('configure_odr_200Hz', ctrl1_writes[-1][1] == 0x0F | (1 << 6))
check_true('configure_fs_500dps', ctrl4_writes[-1][1] == 0x80 | (1 << 4))

# set_full_scale()
gyro_full.set_full_scale(2000)
ctrl4_writes = [w for w in connection.writes if len(w) == 2 and w[0] == REG_CTRL_REG4]
check_true('set_full_scale_2000', ctrl4_writes[-1][1] == 0x80 | (2 << 4))
check_true('set_full_scale_state', gyro_full._full_scale == 2000)

# data_ready and status
connection.set_register(REG_STATUS, 0x08)
check_true('data_ready', gyro_full.data_ready() is True)
check_true('status', gyro_full.status() == 0x08)

# temperature
connection.set_register(REG_OUT_TEMP, 0x80)  # 8-bit signed → -128
check_true('temperature_signed', gyro_full.temperature() == -128)

# power_down / wake_up
gyro_full.power_down()
ctrl1_writes = [w for w in connection.writes if len(w) == 2 and w[0] == REG_CTRL_REG1]
check_true('power_down_clears_pd', (ctrl1_writes[-1][1] & 0x08) == 0)
gyro_full.wake_up()
ctrl1_writes = [w for w in connection.writes if len(w) == 2 and w[0] == REG_CTRL_REG1]
check_true('wake_up_sets_pd', (ctrl1_writes[-1][1] & 0x08) == 0x08)

# sleep
gyro_full.sleep()
ctrl1_writes = [w for w in connection.writes if len(w) == 2 and w[0] == REG_CTRL_REG1]
check_true('sleep_only_pd', ctrl1_writes[-1][1] == 0x08)

# enable_axes
gyro_full.enable_axes(x=False, y=True, z=False)
ctrl1_writes = [w for w in connection.writes if len(w) == 2 and w[0] == REG_CTRL_REG1]
check_true('enable_axes_y_only', (ctrl1_writes[-1][1] & 0x07) == 0x02)

# enable_fifo / disable_fifo
gyro_full.enable_fifo(mode=2, watermark=10)
ctrl5_writes = [w for w in connection.writes if len(w) == 2 and w[0] == REG_CTRL_REG5]
fifo_ctrl_writes = [w for w in connection.writes if len(w) == 2 and w[0] == REG_FIFO_CTRL]
check_true('enable_fifo_sets_fifo_en', (ctrl5_writes[-1][1] & 0x40) == 0x40)
check_true('enable_fifo_mode_wtm', fifo_ctrl_writes[-1][1] == ((2 & 0x7) << 5) | 10)

gyro_full.disable_fifo()
fifo_ctrl_writes = [w for w in connection.writes if len(w) == 2 and w[0] == REG_CTRL_REG5]
check_true('disable_fifo_clears_fifo_en', (fifo_ctrl_writes[-1][1] & 0x40) == 0)

# read_fifo (3 samples). Each sample is 6 bytes (X_L, X_H, Y_L, Y_H, Z_L, Z_H)
# Little-endian: low byte first. Set the OUT register bytes first, then the
# FIFO_SRC count last (set_register writes sequentially through addresses,
# so loading FIFO_SRC before the OUT data would be overwritten by the 18-byte
# burst). Preload at (REG_OUT_X_L | 0x80) so the mock's write_read lookup
# (keyed on the raw sub-address byte the driver sent) hits the test data.
#
# Sensitivity is keyed on the current full-scale (set to 2000 dps earlier).
_sens = 0.07  # 70 mdps/digit for ±2000 dps
_rad = 3.141592653589793 / 180.0
connection.set_register(REG_OUT_X_L | 0x80,
                          0x10, 0x00,    # sample 0: X = +16
                          0x20, 0x00,    # sample 0: Y = +32
                          0x30, 0x00,    # sample 0: Z = +48
                          0x40, 0x00,    # sample 1: X = +64
                          0x50, 0x00,    # sample 1: Y = +80
                          0x60, 0x00,    # sample 1: Z = +96
                          0x70, 0x00,    # sample 2: X = +112
                          0x80, 0x00,    # sample 2: Y = +128
                          0x90, 0x00)    # sample 2: Z = +144
connection.set_register(REG_FIFO_SRC, 3)
samples = gyro_full.read_fifo()
check_true('read_fifo_len', len(samples) == 3)
if len(samples) == 3:
    e1 = 16 * _sens * _rad
    e2 = 32 * _sens * _rad
    e3 = 48 * _sens * _rad
    check_true('read_fifo_sample0_x', abs(samples[0][0] - e1) < 1e-9)
    check_true('read_fifo_sample0_y', abs(samples[0][1] - e2) < 1e-9)
    check_true('read_fifo_sample0_z', abs(samples[0][2] - e3) < 1e-9)

# fifo_samples
connection.set_register(REG_FIFO_SRC, 0x1A)  # 26 stored
check_true('fifo_samples', gyro_full.fifo_samples() == 26)

# enable_highpass / disable_highpass
gyro_full.enable_highpass(mode=2, cutoff=5)
ctrl2_writes = [w for w in connection.writes if len(w) == 2 and w[0] == REG_CTRL_REG2]
ctrl5_writes = [w for w in connection.writes if len(w) == 2 and w[0] == REG_CTRL_REG5]
check_true('enable_highpass_hpcf', ctrl2_writes[-1][1] == (2 << 4) | 5)
check_true('enable_highpass_hpen', (ctrl5_writes[-1][1] & 0x10) == 0x10)
gyro_full.disable_highpass()
ctrl5_writes = [w for w in connection.writes if len(w) == 2 and w[0] == REG_CTRL_REG5]
check_true('disable_highpass_clears_hpen', (ctrl5_writes[-1][1] & 0x10) == 0)

# set_interrupt
gyro_full.set_interrupt(x_high=True, y_high=True, z_high=True, latch=True)
int_cfg_writes = [w for w in connection.writes if len(w) == 2 and w[0] == REG_INT1_CFG]
check_true('set_interrupt_cfg', int_cfg_writes[-1][1] == 0x40 | 0x20 | 0x08 | 0x02)

# set_threshold (at 250 dps default: 1 dps = 1/0.00875 raw)
gyro_full._full_scale = 250
gyro_full.set_threshold('x', 87.5)  # 87.5 / 0.00875 ≈ 10000 raw; float round → 9999 = 0x270F
xh_writes = [w for w in connection.writes if len(w) == 2 and w[0] == REG_INT1_THS_XH]
xl_writes = [w for w in connection.writes if len(w) == 2 and w[0] == REG_INT1_THS_XL]
check_true('set_threshold_xh', xh_writes[-1][1] == (0x270F >> 8) & 0x7F)
check_true('set_threshold_xl', xl_writes[-1][1] == 0x270F & 0xFF)

# set_duration
gyro_full.set_duration(samples=4, wait=True)
dur_writes = [w for w in connection.writes if len(w) == 2 and w[0] == REG_INT1_DURATION]
check_true('set_duration', dur_writes[-1][1] == 0x80 | 4)

# read_int_source
connection.set_register(_drv._REG_INT1_SRC, 0x7F)
check_true('read_int_source', gyro_full.read_int_source() == 0x7F)

# set_data_ready_pin
gyro_full.set_data_ready_pin(True)
ctrl3_writes = [w for w in connection.writes if len(w) == 2 and w[0] == REG_CTRL_REG3]
check_true('set_data_ready_pin', (ctrl3_writes[-1][1] & 0x08) == 0x08)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
