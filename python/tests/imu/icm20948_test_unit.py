import math
import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.imu.icm20948 import ICM20948Full, ICM20948Minimal

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


def s16(value):
    """Encode a signed 16-bit value as its two big-endian bytes."""
    return ((value & 0xFFFF) >> 8) & 0xFF, value & 0xFF


def s16le(value):
    """Encode a signed 16-bit value as its two little-endian bytes."""
    hi, lo = s16(value)
    return lo, hi


class BankedMock(I2CConnectionMock):
    """I2C mock that models the ICM-20948 user banks (REG_BANK_SEL = 0x7F).

    ``registers`` is keyed by ``(bank << 8) | reg``. ``writes`` logs
    ``(bank, bytes)`` so tests can see which bank a write landed in.
    """

    def __init__(self):
        super().__init__()
        self.bank = 0
        self.log = []

    def set_bank_register(self, bank, reg, *values):
        for i, v in enumerate(values):
            self.registers[(bank << 8) | (reg + i)] = v

    def write(self, data):
        data = bytes(data)
        self.writes.append(data)
        self.log.append((self.bank, data))
        if data[0] == 0x7F:
            self.bank = data[1] >> 4
            return
        for i, v in enumerate(data[1:]):
            self.registers[(self.bank << 8) | (data[0] + i)] = v

    def write_read(self, data, n):
        self.writes.append(bytes(data))
        self.log.append((self.bank, bytes(data)))
        return bytes(self.registers.get((self.bank << 8) | (data[0] + i), 0) for i in range(n))


def writes_in(conn, bank):
    return [d for b, d in conn.log if b == bank and len(d) > 1 and d[0] != 0x7F]


connection = BankedMock()
connection.set_bank_register(0, 0x00, 0xEA)
connection.set_bank_register(0, 0x17, 0x40)  # I2C_MST_STATUS: SLV4_DONE

sensor = ICM20948Full(connection)
check_true('init', True)

# init: bank 0, reset, wake, bank 2 config, back to bank 0.
expected = [
    bytes([0x7F, 0x00]),
    bytes([0x00]),
    bytes([0x06, 0x80]),
    bytes([0x06, 0x01]),
    bytes([0x07, 0x00]),
    bytes([0x7F, 0x20]),
    bytes([0x01, 0x19]),
    bytes([0x7F, 0x00]),
    bytes([0x7F, 0x20]),
    bytes([0x14, 0x19]),
    bytes([0x7F, 0x00]),
    bytes([0x7F, 0x20]),
    bytes([0x00, 0x0A]),
    bytes([0x7F, 0x00]),
    bytes([0x7F, 0x20]),
    bytes([0x10, 0x00]),
    bytes([0x7F, 0x00]),
    bytes([0x7F, 0x20]),
    bytes([0x11, 0x0A]),
    bytes([0x7F, 0x00]),
]
check_true('init_writes', connection.writes == expected)
check_true('init_bank_zero', connection.bank == 0)

# accel(): raw (16384, -8192, 4096) at +-2 g.
connection.set_bank_register(0, 0x2D, *s16(16384), *s16(-8192), *s16(4096))
ax, ay, az = sensor.accel()
check_true('accel_x', abs(ax - 9.80665) < 1e-9)
check_true('accel_y', abs(ay + 4.903325) < 1e-9)
check_true('accel_z', abs(az - 2.4516625) < 1e-9)

connection.set_bank_register(0, 0x33, *s16(131), *s16(-131), *s16(262))
gx, gy, gz = sensor.gyro()
check_true('gyro_x', abs(gx - math.radians(1)) < 1e-9)
check_true('gyro_y', abs(gy - math.radians(-1)) < 1e-9)
check_true('gyro_z', abs(gz - math.radians(2)) < 1e-9)

# configure_gyro keeps DLPF/FCHOICE: DLPF 3, FS 2, FCHOICE 1 -> 0x19 | 2<<1 = 0x1D.
sensor.configure_gyro(2)
check_true('configure_gyro_writes', writes_in(connection, 2)[-1] == bytes([0x01, 0x1D]))
connection.set_bank_register(0, 0x33, *s16(328), *s16(0), *s16(0))
gx2, _, _ = sensor.gyro()
check_true('configure_gyro_sensitivity', abs(gx2 - math.radians(10)) < 1e-9)

sensor.configure_accel(1)
check_true('configure_accel_writes', writes_in(connection, 2)[-1] == bytes([0x14, 0x1B]))
connection.set_bank_register(0, 0x2D, *s16(8192), *s16(0), *s16(0))
check_true('configure_accel_sensitivity', abs(sensor.accel()[0] - 9.80665) < 1e-9)

# configure_filters: gyro dlpf 5 bypassed (fchoice 0), accel dlpf 2 enabled.
sensor.configure_filters(5, 2, False, True)
w2 = writes_in(connection, 2)
check_true('configure_filters', w2[-2:] == [bytes([0x01, (5 << 3) | (2 << 1)]),
                                            bytes([0x14, (2 << 3) | (1 << 1) | 1])])
sensor.configure_filters(3, 3, True, True)

# configure_sample_rate: accel divider 0x123 -> DIV_1 = 0x01, DIV_2 = 0x23.
sensor.configure_sample_rate(9, 0x123)
check_true('configure_sample_rate', writes_in(connection, 2)[-3:] == [
    bytes([0x00, 9]), bytes([0x10, 0x01]), bytes([0x11, 0x23])])
check_true('bank_zero_after_config', connection.bank == 0)

# read_all(): 14-byte burst.
connection.set_bank_register(0, 0x2D, *s16(8192), *s16(0), *s16(0),
                             *s16(131 * 2), *s16(0), *s16(0), *s16(340))
(a, g, t) = sensor.read_all()
check_true('read_all_accel', abs(a[0] - 9.80665) < 1e-9)
check_true('read_all_gyro', abs(g[0] - math.radians(262 / 32.8)) < 1e-9)
check_true('read_all_temp', abs(t - (340 / 333.87 + 21.0)) < 1e-9)

connection.set_bank_register(0, 0x39, *s16(340))
check_true('temperature', abs(sensor.temperature() - (340 / 333.87 + 21.0)) < 1e-9)

connection.set_bank_register(0, 0x2D, *s16(100), *s16(-200), *s16(300))
check_true('accel_raw', sensor.accel_raw() == (100, -200, 300))
connection.set_bank_register(0, 0x33, *s16(-50), *s16(60), *s16(-70))
check_true('gyro_raw', sensor.gyro_raw() == (-50, 60, -70))

connection.set_bank_register(0, 0x1A, 0x01)
check_true('data_ready_true', sensor.data_ready() is True)
connection.set_bank_register(0, 0x1A, 0x00)
check_true('data_ready_false', sensor.data_ready() is False)

# set_sensors: accel off -> DISABLE_ACCEL = 111.
sensor.set_sensors(False, True)
check_true('set_sensors_accel_off', connection.writes[-1] == bytes([0x07, 0x38]))
sensor.set_sensors(True, False)
check_true('set_sensors_gyro_off', connection.writes[-1] == bytes([0x07, 0x07]))
sensor.set_sensors(True, True)
check_true('set_sensors_on', connection.writes[-1] == bytes([0x07, 0x00]))

sensor.set_sleep(True)
check_true('set_sleep_true', connection.writes[-1] == bytes([0x06, 0x41]))
sensor.set_sleep(False)
check_true('set_sleep_false', connection.writes[-1] == bytes([0x06, 0x01]))

# gyro offset: 1 dps -> round(1/0.0305) = 33 -> 0x0021; others zero.
sensor.set_gyro_offset(math.radians(1), 0.0, math.radians(-1))
check_true('set_gyro_offset', writes_in(connection, 2)[-1] ==
           bytes([0x03, 0x00, 0x21, 0x00, 0x00, 0xFF, 0xDF]))

# accel offset: code 0x1234 -> H = 0x24, L = (0x1234 << 1) & 0xFE = 0x68.
sensor.set_accel_offset(0x1234, -1, 0)
w1 = writes_in(connection, 1)
check_true('set_accel_offset_x', w1[-3] == bytes([0x14, 0x24, 0x68]))
check_true('set_accel_offset_y', w1[-2] == bytes([0x17, 0xFF, 0xFE]))
connection.set_bank_register(1, 0x14, 0x24, 0x68, 0, 0xFF, 0xFE, 0, 0x00, 0x02)
check_true('get_accel_offset', sensor.get_accel_offset() == (0x1234, -1, 1))
check_true('bank_zero_after_offsets', connection.bank == 0)

connection.set_bank_register(1, 0x28, 0xF6)
check_true('read_timebase_correction', abs(sensor.read_timebase_correction() - (-10 * 0.079)) < 1e-9)

# FIFO.
connection.set_bank_register(0, 0x70, 0x03, 0x45)
check_true('fifo_count', sensor.fifo_count() == 0x345)
connection.set_bank_register(0, 0x70, 0x00, 0x02)
connection.set_bank_register(0, 0x72, 0xAA, 0xBB)
check_true('read_fifo', sensor.read_fifo() == bytes([0xAA, 0xBB]))
check_true('read_fifo_count', sensor.read_fifo(1) == bytes([0xAA]))
connection.set_bank_register(0, 0x70, 0x00, 0x00)
check_true('read_fifo_empty', sensor.read_fifo() == b'')

sensor.enable_fifo(True, True, False)
w0 = writes_in(connection, 0)
check_true('enable_fifo_sources', bytes([0x67, 0x1E]) in w0)
check_true('enable_fifo_user_ctrl', bytes([0x03, 0x40]) in w0)
check_true('enable_fifo_reset', w0[-2:] == [bytes([0x68, 0x1F]), bytes([0x68, 0x00])])
sensor.set_fifo_mode(True)
check_true('set_fifo_mode_snapshot', connection.writes[-1] == bytes([0x69, 0x1F]))
sensor.set_fifo_mode(False)
check_true('set_fifo_mode_stream', connection.writes[-1] == bytes([0x69, 0x00]))

# interrupts.
sensor.set_interrupt_pin(True, False, True, True)
check_true('set_interrupt_pin', connection.writes[-1] == bytes([0x0F, 0xB0]))
sensor.enable_interrupt(ICM20948Full.SOURCE_DATA_READY)
check_true('enable_data_ready', connection.writes[-1] == bytes([0x11, 0x01]))
sensor.enable_interrupt(ICM20948Full.SOURCE_PLL_READY)
check_true('enable_pll', connection.writes[-1] == bytes([0x10, 0x04]))
sensor.disable_interrupt(ICM20948Full.SOURCE_PLL_READY)
check_true('disable_pll', connection.writes[-1] == bytes([0x10, 0x00]))
sensor.enable_interrupt(ICM20948Full.SOURCE_FIFO_OVERFLOW)
check_true('enable_fifo_overflow', connection.writes[-1] == bytes([0x12, 0x1F]))

connection.set_bank_register(0, 0x19, 0x09, 0x01, 0x02)
check_true('poll_interrupt', sensor.poll_interrupt() == 0x01 | 0x02 | 0x04 | 0x10)
connection.set_bank_register(0, 0x19, 0x04, 0x00, 0x00)
check_true('poll_interrupt_pll', sensor.poll_interrupt() == 0x08)

# wake-on-motion: 200 mg -> 50, INTEL_CTRL 0x03, WOM_INT_EN.
sensor.set_wake_on_motion(200)
check_true('wom_thr', bytes([0x13, 50]) in writes_in(connection, 2))
check_true('wom_ctrl', bytes([0x12, 0x03]) in writes_in(connection, 2))
check_true('wom_int_enable', connection.writes[-1] == bytes([0x10, 0x08]))

# low power: LP_EN set last, LP_CONFIG gets the duty-cycle bits.
connection.set_bank_register(0, 0x06, 0x01)
sensor.set_low_power(True, True, 2, 3)
w0 = writes_in(connection, 0)
check_true('low_power_lp_config', bytes([0x05, 0x70]) in w0)
check_true('low_power_lp_en_last', w0[-1] == bytes([0x06, 0x21]))
check_true('low_power_accel_avg', bytes([0x15, 0x02]) in writes_in(connection, 2))
check_true('low_power_gyro_avg', bytes([0x02, 0x03]) in writes_in(connection, 2))
# While LP_EN is set a configuration call clears it first and restores it after.
connection.log.clear()
sensor.configure_gyro(0)
w0 = writes_in(connection, 0)
check_true('lp_guard', w0[0] == bytes([0x06, 0x01]) and w0[-1] == bytes([0x06, 0x21]))
sensor.set_low_power(False, False)
check_true('low_power_off', connection.registers[0x06] & 0x20 == 0)
sensor.configure_gyro(0)
check_true('lp_guard_off', all(d[0] != 0x06 for d in writes_in(connection, 0)[-1:]))

# magnetometer.
connection.set_bank_register(3, 0x17, 0x09)  # slave 4 read returns WIA2 = 0x09
sensor.enable_mag()
check_true('enable_mag_bank_zero', connection.bank == 0)
w3 = writes_in(connection, 3)
check_true('enable_mag_i2c_mst_ctrl', bytes([0x01, 0x07]) in w3)
check_true('enable_mag_reset', bytes([0x16, 0x01]) in w3 and bytes([0x14, 0x32]) in w3)
check_true('enable_mag_mode', bytes([0x16, 0x08]) in w3 and bytes([0x14, 0x31]) in w3)
check_true('enable_mag_slv0', w3[-3:] == [bytes([0x03, 0x8C]), bytes([0x04, 0x10]),
                                         bytes([0x05, 0x89])])
check_true('enable_mag_user_ctrl', connection.registers[0x03] & 0x20 == 0x20)

connection.set_bank_register(0, 0x3B, 0x01, *s16le(1000), *s16le(-500), *s16le(250), 0x00, 0x00)
mx, my, mz = sensor.mag()
check_true('mag_x', abs(mx - 150.0) < 1e-9)
check_true('mag_y', abs(my + 75.0) < 1e-9)
check_true('mag_z', abs(mz - 37.5) < 1e-9)
check_true('mag_raw', sensor.mag_raw() == (1000, -500, 250))
check_true('mag_data_ready', sensor.mag_data_ready() is True)
connection.set_bank_register(0, 0x3B, 0x00)
check_true('mag_data_not_ready', sensor.mag_data_ready() is False)
connection.set_bank_register(0, 0x3B, 0x01, *s16le(1), *s16le(2), *s16le(3), 0x00, 0x08)
try:
    sensor.mag()
    check_true('mag_overflow_raises', False)
except RuntimeError:
    check_true('mag_overflow_raises', True)
check_true('mag_raw_ignores_overflow', sensor.mag_raw() == (1, 2, 3))

sensor.mag_set_mode(2)
check_true('mag_set_mode', bytes([0x16, 0x02]) in writes_in(connection, 3))

# aux read/write via slave 4.
connection.set_bank_register(3, 0x17, 0x5A)
check_true('aux_read_byte', sensor.aux_read_byte(0x1E, 0x0A) == 0x5A)
w3 = writes_in(connection, 3)
check_true('aux_read_addr', bytes([0x13, 0x9E]) in w3)
sensor.aux_write_byte(0x1E, 0x0B, 0xA5)
w3 = writes_in(connection, 3)
check_true('aux_write', bytes([0x13, 0x1E]) in w3 and bytes([0x16, 0xA5]) in w3)
check_true('bank_zero_after_aux', connection.bank == 0)

# magnetometer WIA2 mismatch raises.
bad = BankedMock()
bad.set_bank_register(0, 0x00, 0xEA)
bad.set_bank_register(0, 0x17, 0x40)
bad.set_bank_register(3, 0x17, 0x00)
bad_sensor = ICM20948Full(bad)
try:
    bad_sensor.enable_mag()
    check_true('wia2_mismatch_raises', False)
except ValueError:
    check_true('wia2_mismatch_raises', True)

# NACK on slave 4 raises OSError.
nack = BankedMock()
nack.set_bank_register(0, 0x00, 0xEA)
nack.set_bank_register(0, 0x17, 0x10)
nack_sensor = ICM20948Full(nack)
try:
    nack_sensor.aux_read_byte(0x0C, 0x01)
    check_true('aux_nack_raises', False)
except OSError:
    check_true('aux_nack_raises', True)

# mag() before enable_mag() raises.
fresh = BankedMock()
fresh.set_bank_register(0, 0x00, 0xEA)
fresh_sensor = ICM20948Full(fresh)
for name in ('mag', 'mag_raw', 'mag_data_ready'):
    try:
        getattr(fresh_sensor, name)()
        check_true(name + '_not_enabled_raises', False)
    except RuntimeError:
        check_true(name + '_not_enabled_raises', True)

# self_test returns the (enabled - disabled) response; constant mock -> zeros.
st = fresh_sensor.self_test()
check_true('self_test_shape', len(st) == 2 and len(st[0]) == 3 and len(st[1]) == 3)
check_true('self_test_zero_on_constant', st == ((0.0, 0.0, 0.0), (0.0, 0.0, 0.0)))
check_true('self_test_bank_zero', fresh.bank == 0)

# reset.
fresh_sensor.reset()
check_true('reset', fresh.writes[-1] == bytes([0x06, 0x80]))

# WHO_AM_I mismatch raises during construction.
wrong = BankedMock()
wrong.set_bank_register(0, 0x00, 0x00)
try:
    ICM20948Minimal(wrong)
    check_true('who_am_i_mismatch_raises', False)
except ValueError:
    check_true('who_am_i_mismatch_raises', True)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
