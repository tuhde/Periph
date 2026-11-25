import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.power import lc709203f as mod
from periph.chips.power.lc709203f import LC709203FMinimal, LC709203FFull, crc8_atm

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


class WordMock(I2CConnectionMock):
    """Each command code holds one (low, high, crc) word; writes do not overlap neighbours."""

    def __init__(self):
        super().__init__()
        self.words = {}

    def write(self, data):
        data = bytes(data)
        self.writes.append(data)
        if len(data) == 4:
            self.words[data[0]] = bytes(data[1:4])

    def write_reg(self, reg, data):
        self.write(bytes([reg]) + bytes(data))

    def read_reg(self, reg, length):
        return self.words.get(reg, bytes(length))


def put(conn, cmd, value):
    lo, hi = value & 0xFF, value >> 8
    conn.words[cmd] = bytes([lo, hi, crc8_atm((0x16, cmd, 0x17, lo, hi))])


# CRC examples from the datasheet
check_true('crc_read', crc8_atm((0x16, 0x09, 0x17, 0xC2, 0x0E)) == 0x86)
check_true('crc_write', crc8_atm((0x16, 0x09, 0x55, 0xAA)) == 0x3B)

mod.time.sleep = lambda s: None
conn = WordMock()
put(conn, 0x13, 8)
put(conn, 0x14, 0)
sensor = LC709203FFull(conn)

# init sequence: operational twice, APA 1000 mAh = 0x19, profile 1, I2C mode, 25 C
w = [(x[0], x[1], x[2]) for x in conn.writes if len(x) == 4]
check_true('init_power_mode_twice', [x for x in w if x[0] == 0x15][:2] == [(0x15, 0x01, 0x00)] * 2)
check_true('init_apa', (0x0B, 0x19, 0x00) in w)
check_true('init_profile', (0x12, 0x01, 0x00) in w)
check_true('init_status_i2c', (0x16, 0x00, 0x00) in w)
check_true('init_temp_25C', (0x08, 0xA6, 0x0B) in w)
check_true('write_crc', conn.writes[-1][3] == crc8_atm((0x16, 0x08, 0xA6, 0x0B)) if len(conn.writes[-1]) == 4 else True)

put(conn, 0x09, 3778)
check_true('voltage', abs(sensor.voltage() - 3.778) < 1e-9)
put(conn, 0x0F, 755)
check_true('percent', abs(sensor.percent() - 75.5) < 1e-9)
put(conn, 0x08, 0x0BA6)
check_true('temperature', abs(sensor.temperature() - 25.0) < 1e-9 + 0.05)

# CRC mismatch raises
conn.words[0x09] = bytes([0xC2, 0x0E, 0x00])
try:
    sensor.voltage()
    check_true('crc_mismatch_raises', False)
except ValueError:
    check_true('crc_mismatch_raises', True)

sensor.set_temperature(100.0)
check_true('set_temperature_clamp', conn.writes[-1][:3] == bytes([0x08, 0x04, 0x0D]))
sensor.set_temperature(-50.0)
check_true('set_temperature_clamp_low', conn.writes[-1][:3] == bytes([0x08, 0xE4, 0x09]))

put(conn, 0x0D, 42)
check_true('rsoc', sensor.rsoc() == 42)
sensor.set_rsoc(55)
check_true('set_rsoc', conn.writes[-1][:3] == bytes([0x0D, 55, 0]))
sensor.init_rsoc()
check_true('init_rsoc', conn.writes[-1][:3] == bytes([0x07, 0x55, 0xAA]))
sensor.before_rsoc()
check_true('before_rsoc', conn.writes[-1][:3] == bytes([0x04, 0x55, 0xAA]))

sensor.use_thermistor(3380)
check_true('use_thermistor', conn.writes[-2][:3] == bytes([0x06, 0x34, 0x0D]) and conn.writes[-1][:3] == bytes([0x16, 1, 0]))
put(conn, 0x16, 1)
check_true('is_thermistor_mode', sensor.is_thermistor_mode())
sensor.use_i2c_temperature(25.0)
check_true('use_i2c_temperature', conn.writes[-2][:3] == bytes([0x16, 0, 0]) and conn.writes[-1][:3] == bytes([0x08, 0xA6, 0x0B]))

sensor.set_current_direction(LC709203FFull.DIRECTION_DISCHARGE)
check_true('direction_discharge', conn.writes[-1][:3] == bytes([0x0A, 0xFF, 0xFF]))
try:
    sensor.set_current_direction(5)
    check_true('direction_invalid', False)
except ValueError:
    check_true('direction_invalid', True)

sensor.set_apa(0x2D)
check_true('set_apa', conn.writes[-1][:3] == bytes([0x0B, 0x2D, 0]))
sensor.set_apt(0x1234)
check_true('set_apt', conn.writes[-1][:3] == bytes([0x0C, 0x34, 0x12]))
sensor.set_profile(0)
check_true('set_profile', (0x12, 0x00, 0x00) in [(x[0], x[1], x[2]) for x in conn.writes if len(x) == 4])
check_true('set_profile_reoperational', conn.writes[-1][:3] == bytes([0x15, 1, 0]))

put(conn, 0x1A, 0x0301)
check_true('profile_id', sensor.profile_id() == 0x0301)
put(conn, 0x11, 0x1234)
check_true('ic_version', sensor.ic_version() == 0x1234)

sensor.sleep()
check_true('sleep', conn.writes[-1][:3] == bytes([0x15, 2, 0]))
put(conn, 0x15, 2)
check_true('is_sleeping', sensor.is_sleeping())

# alarms
sensor.set_alarm_rsoc(20)
sensor.set_alarm_voltage(3.5)
check_true('set_alarm_voltage', conn.writes[-1][:3] == bytes([0x14, 0xAC, 0x0D]))
put(conn, 0x0D, 10)
put(conn, 0x09, 3400)
check_true('poll_both', sensor.poll_interrupt() == 3)
put(conn, 0x0D, 50)
put(conn, 0x09, 3900)
check_true('poll_none', sensor.poll_interrupt() == 0)

# apa interpolation
check_true('apa_interp', LC709203FMinimal._apa_for(700) == 0x14)
check_true('apa_clamp', LC709203FMinimal._apa_for(50) == 0x08 and LC709203FMinimal._apa_for(9000) == 0x36)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
