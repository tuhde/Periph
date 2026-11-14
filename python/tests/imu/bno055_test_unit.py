import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.imu.bno055 import BNO055Full, BNO055Minimal

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


def check_close(label, got, expected, tol=1e-4):
    check_true(label, abs(got - expected) <= tol)


def le16(value):
    value &= 0xFFFF
    return value & 0xFF, value >> 8


def make(full=True):
    conn = I2CConnectionMock({0x00: 0xA0, 0x01: 0xFB, 0x02: 0x32, 0x03: 0x0F,
                              0x04: 0x11, 0x05: 0x03, 0x06: 0x15})
    chip = (BNO055Full if full else BNO055Minimal)(conn)
    return conn, chip


def reg_writes(conn, reg):
    return [w[1] for w in conn.writes if len(w) == 2 and w[0] == reg]


def vec(conn, reg, values):
    out = []
    for v in values:
        out.extend(le16(v))
    conn.set_register(reg, *out)


# init sequence
conn, m = make(False)
writes = [bytes(w) for w in conn.writes if len(w) == 2]
check_true('init_pwr_mode', bytes([0x3E, 0x00]) in writes)
check_true('init_page0', bytes([0x07, 0x00]) in writes)
check_true('init_unit_sel', bytes([0x3B, 0x86]) in writes)
check_true('init_opr_ndof', writes[-1] == bytes([0x3D, 0x0C]))

# wrong chip id
try:
    bad = I2CConnectionMock({0x00: 0x55})
    BNO055Minimal(bad)
    check_true('bad_chip_id', False)
except ValueError:
    check_true('bad_chip_id', True)

# euler: heading 90 deg = pi/2 rad -> raw 1414, roll -1 rad -> -900, pitch 0.5 rad -> 450
vec(conn, 0x1A, [1414, -900, 450])
h, r, p = m.euler()
check_close('euler_heading', h, 1414 / 900.0)
check_close('euler_roll', r, -1.0)
check_close('euler_pitch', p, 0.5)
check_true('minimal_no_full_api', not hasattr(m, 'quaternion'))

conn, s = make()

# vectors
vec(conn, 0x20, [16384, -8192, 0, 4096])
q = s.quaternion()
check_true('quaternion', q == (1.0, -0.5, 0.0, 0.25))
vec(conn, 0x28, [100, -250, 981])
check_true('linear_acceleration', s.linear_acceleration() == (1.0, -2.5, 9.81))
vec(conn, 0x2E, [0, 0, 981])
check_true('gravity', s.gravity() == (0.0, 0.0, 9.81))
vec(conn, 0x08, [-100, 200, 300])
check_true('acceleration', s.acceleration() == (-1.0, 2.0, 3.0))
vec(conn, 0x0E, [160, -32, 16])
check_true('magnetic_field', s.magnetic_field() == (10.0, -2.0, 1.0))
vec(conn, 0x14, [900, 0, -450])
check_true('angular_rate', s.angular_rate() == (1.0, 0.0, -0.5))
conn.set_register(0x34, 0xE7)
check_true('temperature_negative', s.temperature() == -25)
conn.set_register(0x34, 23)
check_true('temperature', s.temperature() == 23)

# calibration status
conn.set_register(0x35, 0xE4)
check_true('calibration_status', s.calibration_status() == (3, 2, 1, 0))
check_true('not_calibrated', not s.is_calibrated())
conn.set_register(0x35, 0xFF)
check_true('is_calibrated', s.is_calibrated())

# modes
conn.writes[:] = []
s.set_mode(BNO055Full.MODE_IMU)
check_true('set_mode_sequence', [bytes(w) for w in conn.writes] == [bytes([0x3D, 0x00]), bytes([0x3D, 0x08])])
conn.set_register(0x3D, 0x08)
check_true('get_mode', s.get_mode() == 8)
conn.writes[:] = []
s.set_mode(BNO055Full.MODE_CONFIG)
check_true('set_mode_config_only', [bytes(w) for w in conn.writes] == [bytes([0x3D, 0x00])])
try:
    s.set_mode(13)
    check_true('set_mode_range', False)
except ValueError:
    check_true('set_mode_range', True)
s.set_mode(BNO055Full.MODE_NDOF)

# calibration blob
blob = bytes(range(1, 23))
conn.set_register(0x55, *blob)
conn.writes[:] = []
check_true('get_calibration', s.get_calibration() == blob)
ops = [bytes(w) for w in conn.writes]
check_true('get_calibration_config', ops[0] == bytes([0x3D, 0x00]) and ops[-1] == bytes([0x3D, 0x0C]))
conn.writes[:] = []
s.set_calibration(blob)
data_writes = [bytes(w) for w in conn.writes if 0x55 <= w[0] <= 0x6A]
check_true('set_calibration', data_writes == [bytes([0x55 + i, blob[i]]) for i in range(22)])
check_true('set_calibration_restores_mode', bytes(conn.writes[-1]) == bytes([0x3D, 0x0C]))
try:
    s.set_calibration(b'\x00' * 21)
    check_true('set_calibration_len', False)
except ValueError:
    check_true('set_calibration_len', True)

# configuration
conn.writes[:] = []
s.set_axis_remap(0x21, 0x02)
check_true('axis_remap', reg_writes(conn, 0x41) == [0x21] and reg_writes(conn, 0x42) == [0x02])
check_true('axis_remap_restores', reg_writes(conn, 0x3D) == [0x00, 0x0C])
conn.writes[:] = []
s.set_power_mode('low_power')
check_true('power_mode', reg_writes(conn, 0x3E) == [1])
s.set_power_mode('suspend')
check_true('power_mode_suspend', reg_writes(conn, 0x3E) == [1, 2])
try:
    s.set_power_mode('bogus')
    check_true('power_mode_invalid', False)
except ValueError:
    check_true('power_mode_invalid', True)
conn.writes[:] = []
s.set_temperature_source('gyroscope')
check_true('temp_source', reg_writes(conn, 0x40) == [1])
conn.writes[:] = []
s.use_external_crystal(True)
check_true('crystal_on', reg_writes(conn, 0x3F) == [0x80])
s.use_external_crystal(False)
check_true('crystal_off', reg_writes(conn, 0x3F) == [0x80, 0x00])

# self-test
conn.set_register(0x36, 0x0D)
conn.writes[:] = []
st = s.self_test()
check_true('self_test_trigger', reg_writes(conn, 0x3F) == [0x01])
check_true('self_test_result', st == {'acc': True, 'mag': False, 'gyr': True, 'mcu': True})
check_true('self_test_restores_mode', reg_writes(conn, 0x3D) == [0x00, 0x0C])

# status and revisions
conn.set_register(0x39, 5, 0)
check_true('system_status', s.system_status() == (5, 0))
conn.set_register(0x39, 1, 3)
check_true('system_error', s.system_status() == (1, 3))
rev = s.revisions()
check_true('revisions', rev['chip_id'] == 0xA0 and rev['acc_id'] == 0xFB and rev['mag_id'] == 0x32 and
           rev['gyr_id'] == 0x0F and rev['sw_revision'] == 0x0311 and rev['bootloader'] == 0x15)

# unique id (page 1)
conn.set_register(0x50, *range(16))
conn.writes[:] = []
check_true('unique_id', s.unique_id() == bytes(range(16)))
check_true('unique_id_pages', reg_writes(conn, 0x07) == [1, 0])

# interrupts
conn.set_register(0x10, 0x00)
conn.set_register(0x0F, 0x00)
conn.writes[:] = []
s.enable_interrupt(BNO055Full.SOURCE_ACC_BSX_DRDY | BNO055Full.SOURCE_GYR_DRDY)
check_true('enable_int_pages', reg_writes(conn, 0x07) == [1, 0])
check_true('enable_int_en', reg_writes(conn, 0x10) == [0x11])
check_true('enable_int_msk', reg_writes(conn, 0x0F) == [0x11])
conn.set_register(0x10, 0x11)
conn.set_register(0x0F, 0x11)
conn.writes[:] = []
s.disable_interrupt(BNO055Full.SOURCE_GYR_DRDY)
check_true('disable_int', reg_writes(conn, 0x10) == [0x01] and reg_writes(conn, 0x0F) == [0x01])
conn.set_register(0x37, 0x41)
check_true('poll_interrupt', s.poll_interrupt() == (BNO055Full.SOURCE_ACC_BSX_DRDY | BNO055Full.SOURCE_ACC_AM))


class FakePin:
    RISING = 1

    def __init__(self):
        self.handler = None

    def on_edge(self, handler, edge):
        self.handler = handler

    def off_edge(self, handler):
        if self.handler is handler:
            self.handler = None


pin = FakePin()
seen = []
s.on_interrupt(seen.append, pin)
check_true('on_interrupt_registers', pin.handler is not None)
pin.handler(pin)
check_true('on_interrupt_callback', seen == [0x00] or seen == [0x41])
s.off_interrupt()
check_true('off_interrupt', pin.handler is None)

# reset
conn.writes[:] = []
s.reset()
check_true('reset_trigger', reg_writes(conn, 0x3F)[0] == 0x20)
check_true('reset_reinit', reg_writes(conn, 0x3D)[-1] == 0x0C and reg_writes(conn, 0x3B)[-1] == 0x86)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
