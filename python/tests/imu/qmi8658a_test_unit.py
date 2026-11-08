import math
import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.imu.qmi8658a import QMI8658AFull, QMI8658AMinimal

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


def check_close(label, got, expected, tol=1e-3):
    check_true(label, abs(got - expected) <= tol)


def le16(value):
    """Encode a signed 16-bit value as two little-endian bytes."""
    value &= 0xFFFF
    return value & 0xFF, value >> 8


class QmiMock(I2CConnectionMock):
    """I2C mock that models the CTRL9 handshake and the FIFO_DATA port."""

    def __init__(self):
        super().__init__()
        self.registers[0x00] = 0x05
        self.fifo = bytearray()
        self.cmds = []                 # (command, CAL1..CAL4_H snapshot)

    def write(self, data):
        super().write(data)
        data = bytes(data)
        if data[0] in (0x03, 0x04) and len(data) == 2:     # self-test start / end
            st = self.registers.get(0x2D, 0)
            self.registers[0x2D] = (st | 0x01) if data[1] & 0x80 else (st & ~0x01)
        if data[0] == 0x0A and len(data) == 2:
            if data[1] != 0:
                self.cmds.append((data[1], bytes(self.registers.get(0x0B + i, 0) for i in range(8))))
                self.registers[0x2D] = self.registers.get(0x2D, 0) | 0x80
            else:
                self.registers[0x2D] = self.registers.get(0x2D, 0) & ~0x80

    def write_read(self, data, n):
        if data[0] == 0x17:
            out = bytes(self.fifo[:n])
            del self.fifo[:n]
            self.writes.append(bytes(data))
            return out
        return super().write_read(data, n)

    def reg_writes(self, reg):
        return [w[1] for w in self.writes if w[0] == reg and len(w) == 2]


def make(full=True):
    conn = QmiMock()
    return conn, (QMI8658AFull(conn) if full else QMI8658AMinimal(conn))


# init sequence
conn, s = make()
init = [w for w in conn.writes if len(w) == 2]
check_true('init_reset', init[0] == bytes([0x60, 0xB0]))
check_true('init_ctrl1', bytes([0x02, 0x40]) in init)
check_true('init_ctrl8', bytes([0x09, 0x80]) in init)
check_true('init_ctrl2', bytes([0x03, 0x05]) in init)
check_true('init_ctrl3', bytes([0x04, 0x45]) in init)
check_true('init_ctrl5', bytes([0x06, 0x77]) in init)
check_true('init_ctrl7', init[-1] == bytes([0x08, 0x03]))

# WHO_AM_I mismatch
bad = I2CConnectionMock()
try:
    QMI8658AMinimal(bad)
    check_true('who_am_i_mismatch_raises', False)
except ValueError:
    check_true('who_am_i_mismatch_raises', True)

# accel / gyro conversion (±2 g, ±256 dps)
conn.set_register(0x35, *le16(16384), *le16(-8192), *le16(0))
ax, ay, az = s.accel()
check_close('accel_x', ax, 9.80665)
check_close('accel_y', ay, -4.903325)
check_close('accel_z', az, 0.0)
conn.set_register(0x3B, *le16(128), *le16(-256), *le16(64))
gx, gy, gz = s.gyro()
check_close('gyro_x', gx, math.radians(1.0), 1e-6)
check_close('gyro_y', gy, -math.radians(2.0), 1e-6)
check_close('gyro_z', gz, math.radians(0.5), 1e-6)

# raw + read_all + temperature
conn.set_register(0x33, *le16(0x1980), *le16(2048), *le16(0), *le16(-2048),
                  *le16(16), *le16(32), *le16(-64))
check_true('accel_raw', s.accel_raw() == (2048, 0, -2048))
check_true('gyro_raw', s.gyro_raw() == (16, 32, -64))
accel, gyro, temp = s.read_all()
check_close('read_all_temp', temp, 25.5)
check_close('read_all_accel_x', accel[0], 2048 / 16384 * 9.80665)
check_close('read_all_gyro_z', gyro[2], -64 / 128 * math.pi / 180, 1e-6)
check_close('temperature', s.temperature(), 25.5)

# timestamp / data_ready
conn.set_register(0x30, 0x01, 0x02, 0x03)
check_true('timestamp', s.timestamp() == 0x030201)
conn.set_register(0x2E, 0x00)
check_true('data_ready_false', not s.data_ready())
conn.set_register(0x2E, 0x02)
check_true('data_ready_true', s.data_ready())

# configure_accel / configure_gyro update sensitivity
s.configure_accel(2, 4)
check_true('configure_accel_write', conn.writes[-1] == bytes([0x03, 0x24]))
conn.set_register(0x35, *le16(4096), 0, 0, 0, 0)
check_close('accel_after_range', s.accel()[0], 9.80665)
s.configure_gyro(7, 3)
check_true('configure_gyro_write', conn.writes[-1] == bytes([0x04, 0x73]))
conn.set_register(0x3B, *le16(16), 0, 0, 0, 0)
check_close('gyro_after_range', s.gyro()[0], math.radians(1.0), 1e-6)
try:
    s.configure_accel(4, 5)
    check_true('configure_accel_range_raises', False)
except ValueError:
    check_true('configure_accel_range_raises', True)
s.configure_accel()
s.configure_gyro()

# filters
s.configure_filters(True, 3, False, 1)
check_true('configure_filters', conn.writes[-1] == bytes([0x06, 0x27]))

# sensors / power
s.set_sensors(True, False)
check_true('set_sensors_accel', conn.writes[-1] == bytes([0x08, 0x01]))
s.set_sensors(True, True, True)
check_true('set_sensors_snooze', conn.writes[-1] == bytes([0x08, 0x13]))
s.set_sensors(True, True)
s.set_power_down(True)
check_true('power_down_ctrl1', conn.writes[-1] == bytes([0x02, 0x41]))
check_true('power_down_ctrl7', conn.writes[-2] == bytes([0x08, 0x00]))
s.set_power_down(False)
check_true('wake_ctrl1', conn.writes[-1] == bytes([0x02, 0x40]))
s.set_sensors(True, True)

# sync sample
conn.cmds.clear()
s.set_sync_sample(True)
check_true('sync_gating_cmd', conn.cmds[-1][0] == 0x12 and conn.cmds[-1][1][0] == 1)
check_true('sync_ctrl7', bytes([0x08, 0x83]) in conn.writes)
s.configure_accel()
conn.registers[0x2D] = 0x03
conn.set_register(0x35, *le16(16384), 0, 0, 0, 0)
check_close('sync_accel', s.accel()[0], 9.80665)
s.set_sync_sample(False)
check_true('sync_off_ctrl7', bytes([0x08, 0x03]) in conn.writes)
check_true('sync_gating_restore', conn.cmds[-1][0] == 0x12 and conn.cmds[-1][1][0] == 0)

# FIFO
s.configure_fifo(2, 1, 16)
check_true('fifo_ctrl', conn.writes[-1] == bytes([0x14, 0x06]))
check_true('fifo_wtm', bytes([0x13, 0x10]) in conn.writes)
conn.set_register(0x16, 0x00)
conn.set_register(0x15, 12)
check_true('fifo_count', s.fifo_count() == 24)
check_true('fifo_status', s.fifo_status() == 0x00)
conn.fifo = bytearray(range(24))
conn.cmds.clear()
data = s.read_fifo()
check_true('read_fifo_data', data == bytes(range(24)))
check_true('read_fifo_req', conn.cmds[0][0] == 0x05)
check_true('read_fifo_ai_off', bytes([0x02, 0x00]) in conn.writes)
check_true('read_fifo_ai_restored', bytes([0x02, 0x40]) in conn.writes)
check_true('read_fifo_rd_mode_cleared', conn.writes[-1] == bytes([0x14, 0x06]))
conn.fifo = bytearray(range(24))
check_true('read_fifo_count_limit', s.read_fifo(10) == bytes(range(10)))
s.reset_fifo()
check_true('reset_fifo', conn.cmds[-1][0] == 0x04)
s.configure_fifo(0, 3, 0)
check_true('fifo_bypass_drdy', conn.writes[-1] == bytes([0x08, 0x03]))

# motion engines: both sets sent with sensors off
conn.cmds.clear()
s.configure_any_motion(0.25, 3, 0b111, False)
check_true('motion_two_sets', [c[0] for c in conn.cmds] == [0x0E, 0x0E])
c1, c2 = conn.cmds[0][1], conn.cmds[1][1]
check_true('motion_set1', c1[0] == 8 and c1[3] == 4 and c1[6] == 0xF7 and c1[7] == 0x10)
check_true('motion_set2', c2[0] == 3 and c2[1] == 30 and c2[2] == 150 and c2[4] == (600 & 0xFF)
           and c2[5] == (600 >> 8) and c2[7] == 0x20)
check_true('motion_sensors_off', bytes([0x08, 0x00]) in conn.writes)
check_true('motion_sensors_restored', conn.writes[-1] == bytes([0x08, 0x03]))
s.configure_no_motion(0.5, 10, 0b011, False)
s.configure_significant_motion(100, 300)
check_true('sig_motion_set2', conn.cmds[-1][1][2] == 100 and conn.cmds[-1][1][4] == (300 & 0xFF)
           and conn.cmds[-1][1][5] == 1)
s.enable_motion(True, True, False)
check_true('enable_motion', conn.writes[-1] == bytes([0x09, 0x86]))
s.enable_motion()
check_true('disable_motion', conn.writes[-1] == bytes([0x09, 0x80]))

# tap
conn.cmds.clear()
s.configure_tap()
check_true('tap_two_sets', [c[0] for c in conn.cmds] == [0x0C, 0x0C])
t1, t2 = conn.cmds[0][1], conn.cmds[1][1]
check_true('tap_set1', t1[0] == 20 and t1[1] == 0 and t1[2] == 50 and t1[4] == 250 and t1[7] == 0x10)
check_true('tap_set2', t2[0] == 8 and t2[1] == 32 and t2[2:4] == bytes([0x33, 0x03]) and
           t2[4:6] == bytes([0x9A, 0x01]) and t2[7] == 0x20)
s.enable_tap(True)
check_true('enable_tap', conn.writes[-1] == bytes([0x09, 0x81]))
conn.set_register(0x59, 0xA2)
check_true('tap_status', s.tap_status() == (2, 2, True))
s.enable_tap(False)

# pedometer
conn.cmds.clear()
s.configure_pedometer()
check_true('ped_two_sets', [c[0] for c in conn.cmds] == [0x0D, 0x0D])
p1, p2 = conn.cmds[0][1], conn.cmds[1][1]
check_true('ped_set1', p1[0] == 50 and p1[2] == 205 and p1[4] == 102 and p1[7] == 0x10)
check_true('ped_set2', p2[0] == 200 and p2[2] == 20 and p2[3] == 10 and p2[5] == 4 and p2[7] == 0x20)
s.enable_pedometer(True)
check_true('enable_pedometer', conn.writes[-1] == bytes([0x09, 0x90]))
conn.set_register(0x5A, 0x34, 0x12, 0x00)
check_true('step_count', s.step_count() == 0x1234)
s.reset_step_count()
check_true('reset_step_count', conn.cmds[-1][0] == 0x0F)
s.enable_pedometer(False)

# wake on motion
conn.cmds.clear()
s.set_wake_on_motion(100, interrupt_pin=2, initial_level=1, blanking_samples=5)
check_true('wom_ctrl2', bytes([0x03, 0x0C]) in conn.writes)
check_true('wom_cmd', conn.cmds[-1][0] == 0x08 and conn.cmds[-1][1][0] == 100
           and conn.cmds[-1][1][1] == 0xC5)
check_true('wom_accel_on', conn.writes[-1] == bytes([0x08, 0x01]) or bytes([0x08, 0x01]) in conn.writes)
check_true('wom_int2_enabled', (conn.reg_writes(0x02)[-1] & 0x10) != 0)
s.set_wake_on_motion(0)
check_true('wom_exit', conn.cmds[-1][0] == 0x08 and conn.cmds[-1][1][0] == 0)
check_true('wom_exit_int_off', (conn.reg_writes(0x02)[-1] & 0x18) == 0)
s.configure_accel()
s.set_sensors(True, True)

# offsets
conn.cmds.clear()
s.set_accel_offset(9.80665, 0.0, -9.80665)
cmd, cal = conn.cmds[-1]
check_true('accel_offset', cmd == 0x09 and cal[0:2] == bytes([0x00, 0x10]) and cal[4:6] == bytes([0x00, 0xF0]))
s.set_gyro_offset(math.radians(1.0), 0.0, 0.0)
cmd, cal = conn.cmds[-1]
check_true('gyro_offset', cmd == 0x0A and cal[0:2] == bytes([32, 0]))

# calibrate_gyro
conn.set_register(0x46, 0x00)
ok, status = s.calibrate_gyro()
check_true('calibrate_gyro', ok and status == 0 and conn.cmds[-1][0] == 0xA2)
conn.set_register(0x46, 0x01)
ok, status = s.calibrate_gyro()
check_true('calibrate_gyro_fail', (not ok) and status == 1)
conn.set_register(0x51, *le16(1000), *le16(2000), *le16(3000))
check_true('get_gyro_gains', s.get_gyro_gains() == (1000, 2000, 3000))
s.set_gyro_gains(1000, 2000, 3000)
check_true('set_gyro_gains', conn.cmds[-1][0] == 0xAA and conn.cmds[-1][1][0:6] ==
           bytes([0xE8, 0x03, 0xD0, 0x07, 0xB8, 0x0B]))

# device_info
conn.set_register(0x01, 0x7C)
conn.set_register(0x49, 1, 2, 0, 3)
conn.set_register(0x4B, 3)
conn.set_register(0x51, 1, 2, 3, 4, 5, 6)
info = s.device_info()
check_true('device_info', info['revision'] == 0x7C and info['firmware'] == bytes([1, 2, 3])
           and info['usid'] == bytes([1, 2, 3, 4, 5, 6]) and conn.cmds[-1][0] == 0x10)

# pull-ups
s.set_pullups(aux=False, i2c=False)
check_true('set_pullups', conn.cmds[-1][0] == 0x11 and conn.cmds[-1][1][0] == 0x09)

# self-test
conn.set_register(0x2D, 0x00)
conn.set_register(0x51, *le16(2048), *le16(2048), *le16(-2048), 0, 0, 0)
passed_a, vals = s.self_test_accel()
check_true('self_test_accel', passed_a and abs(vals[0] - 9.80665) < 1e-3)
conn.set_register(0x51, *le16(16 * 400), *le16(16 * 400), *le16(16 * 400))
passed_g, vals = s.self_test_gyro()
check_true('self_test_gyro', passed_g and abs(vals[0] - math.radians(400.0)) < 1e-3)
conn.set_register(0x51, *le16(16 * 100), *le16(16 * 400), *le16(16 * 400))
check_true('self_test_gyro_fail', not s.self_test_gyro()[0])
conn.set_register(0x2D, 0x00)

# interrupts
s.set_interrupt_pins(1, 1)
check_true('int_pins_ctrl8', conn.reg_writes(0x09)[-1] & 0x40)
s.enable_interrupt(QMI8658AFull.SOURCE_TAP)
check_true('int_tap_engine', conn.reg_writes(0x09)[-1] & 0x01)
check_true('int_tap_int1', conn.reg_writes(0x02)[-1] & 0x08)
s.enable_interrupt(QMI8658AFull.SOURCE_DATA_READY)
check_true('int_drdy_int2', conn.reg_writes(0x02)[-1] & 0x10)
s.disable_interrupt(QMI8658AFull.SOURCE_DATA_READY)
check_true('int_drdy_off', not (conn.reg_writes(0x02)[-1] & 0x10))
check_true('int_drdy_dis', conn.reg_writes(0x08)[-1] & 0x20)
s.disable_interrupt(QMI8658AFull.SOURCE_TAP)
check_true('int_tap_int1_off', not (conn.reg_writes(0x02)[-1] & 0x08))
s.set_interrupt_pins(2, 2)
conn.set_register(0x2E, 0x01, 0xA2)
conn.set_register(0x16, 0xC0)
st = s.poll_interrupt()
expect = (QMI8658AFull.SOURCE_DATA_READY | QMI8658AFull.SOURCE_FIFO_WATERMARK |
          QMI8658AFull.SOURCE_FIFO_FULL | QMI8658AFull.SOURCE_ANY_MOTION |
          QMI8658AFull.SOURCE_SIGNIFICANT_MOTION | QMI8658AFull.SOURCE_TAP)
check_true('poll_interrupt', st == expect)

# reset
s.reset()
check_true('reset_write', bytes([0x60, 0xB0]) in conn.writes[-4:])

# minimal-only class has no Full API
conn2, m = make(False)
check_true('minimal_no_full_api', not hasattr(m, 'read_all'))

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
