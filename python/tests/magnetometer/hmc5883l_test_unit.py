import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.magnetometer.hmc5883l import HMC5883LMinimal, HMC5883LFull

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


def close(a, b, eps=1e-9):
    return a is not None and b is not None and abs(a - b) < eps


def last_write_to(connection, reg):
    for w in reversed(connection.writes):
        if len(w) == 2 and w[0] == reg:
            return w[1]
    return None


# --- Minimal constructor: writes Config A, Config B, Mode ---
connection = I2CConnectionMock()
chip = HMC5883LMinimal(connection)
check_true('init_writes_config_a', last_write_to(connection, 0x00) == 0x70)
check_true('init_writes_config_b', last_write_to(connection, 0x01) == 0x20)
check_true('init_writes_mode', last_write_to(connection, 0x02) == 0x00)

# --- magnetic_field(): X,Z,Y wire order -> (x,y,z), gain=1 (1090 LSb/Gauss) ---
# bytes: X MSB/LSB, Z MSB/LSB, Y MSB/LSB
connection.set_register(0x03, 0x03, 0xE8, 0xFE, 0x0C, 0x07, 0xD0)  # x=1000, z=-500, y=2000
x, y, z = chip.magnetic_field()
check_true('magnetic_field_x', close(x, (1000 / 1090) * 1e-4))
check_true('magnetic_field_y', close(y, (2000 / 1090) * 1e-4))
check_true('magnetic_field_z', close(z, (-500 / 1090) * 1e-4))

# --- magnetic_field(): overflow sentinel returns None ---
connection.set_register(0x03, 0xF0, 0x00, 0x03, 0xE8, 0x03, 0xE8)  # x overflow, z/y=1000
x, y, z = chip.magnetic_field()
check_true('magnetic_field_overflow_x_none', x is None)
check_true('magnetic_field_overflow_y_not_none', y is not None)

# --- Full: configure() writes Config A/B, updates cached gain ---
full_conn = I2CConnectionMock()
full = HMC5883LFull(full_conn)
full.configure(odr=30, averaging=4, gain=5)
# MA=10 (4 avg), DO=101 (30Hz) -> config_a = (0b10<<5)|(0b101<<2) = 0x54
check_true('configure_config_a', last_write_to(full_conn, 0x00) == 0x54)
# GN=101 (gain 5) -> config_b = 0b101<<5 = 0xA0
check_true('configure_config_b', last_write_to(full_conn, 0x01) == 0xA0)
check_true('configure_updates_gain', full._gain == 5)

try:
    full.configure(averaging=3)
    check_true('configure_bad_averaging_raises', False)
except ValueError:
    check_true('configure_bad_averaging_raises', True)
try:
    full.configure(odr=100)
    check_true('configure_bad_odr_raises', False)
except ValueError:
    check_true('configure_bad_odr_raises', True)
try:
    full.configure(gain=8)
    check_true('configure_bad_gain_raises', False)
except ValueError:
    check_true('configure_bad_gain_raises', True)

# --- set_gain() ---
full.set_gain(2)
check_true('set_gain_writes_config_b', last_write_to(full_conn, 0x01) == (2 << 5))
check_true('set_gain_updates_cache', full._gain == 2)
try:
    full.set_gain(9)
    check_true('set_gain_invalid_raises', False)
except ValueError:
    check_true('set_gain_invalid_raises', True)

# --- set_mode() ---
full.set_mode('single')
check_true('set_mode_single', last_write_to(full_conn, 0x02) == 0b01)
full.set_mode('idle')
check_true('set_mode_idle', last_write_to(full_conn, 0x02) == 0b10)
full.set_mode('continuous')
check_true('set_mode_continuous', last_write_to(full_conn, 0x02) == 0b00)
try:
    full.set_mode('bogus')
    check_true('set_mode_invalid_raises', False)
except ValueError:
    check_true('set_mode_invalid_raises', True)

# --- data_ready()/status() ---
full_conn.set_register(0x09, 0x01)
check_true('data_ready_true', full.data_ready() is True)
check_true('status_raw', full.status() == 0x01)
full_conn.set_register(0x09, 0x02)  # LOCK set, RDY clear
check_true('data_ready_false', full.data_ready() is False)

# --- single_measurement(): writes mode=0x01, then reads data ---
full_conn.set_register(0x03, 0x03, 0xE8, 0xFE, 0x0C, 0x07, 0xD0)  # x=1000,z=-500,y=2000
sx, sy, sz = full.single_measurement()
check_true('single_measurement_writes_mode', last_write_to(full_conn, 0x02) == 0x01)
check_true('single_measurement_x', close(sx, (1000 / full._gain_lsb_per_gauss) * 1e-4))

# --- identify() ---
full_conn.set_register(0x0A, 0x48, 0x34, 0x33)
check_true('identify', full.identify() == (0x48, 0x34, 0x33))

# --- self_test(): sets MS bias bits, restores normal mode afterward ---
selftest_conn = I2CConnectionMock()
selftest = HMC5883LFull(selftest_conn)
selftest_conn.set_register(0x00, 0x70)  # current Config A (post-init)
selftest_conn.set_register(0x03, 0x03, 0xE8, 0xFE, 0x0C, 0x07, 0xD0)
result = selftest.self_test(positive=True)
writes_a = [w[1] for w in selftest_conn.writes if len(w) == 2 and w[0] == 0x00]
check_true('self_test_sets_positive_bias', 0x71 in writes_a)  # 0x70 | 0b01
check_true('self_test_restores_normal', writes_a[-1] == 0x70)  # 0x71 & 0xFC | 0b00
check_true('self_test_result', close(result[0], (1000 / selftest._gain_lsb_per_gauss) * 1e-4))

selftest_conn.set_register(0x00, 0x70)
writes_a_before = len([w for w in selftest_conn.writes if len(w) == 2 and w[0] == 0x00])
selftest.self_test(positive=False)
writes_a = [w[1] for w in selftest_conn.writes if len(w) == 2 and w[0] == 0x00]
check_true('self_test_sets_negative_bias', 0x72 in writes_a[writes_a_before:])  # 0x70 | 0b10

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
