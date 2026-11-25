import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.pressure.mpl3115a2 import MPL3115A2Minimal, MPL3115A2Full

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


def writes_to(connection, reg):
    return [w for w in connection.writes if len(w) >= 2 and w[0] == reg]


class Mpl3115Mock(I2CConnectionMock):
    """RST (0x04) and OST (0x02) are self-clearing in CTRL_REG1 on the real chip."""

    def write(self, data):
        super().write(data)
        data = bytes(data)
        if len(data) == 2 and data[0] == 0x26:
            self.registers[0x26] = data[1] & ~0x06 & 0xFF


def make():
    c = Mpl3115Mock()
    c.set_register(0x0C, 0xC4)
    c.set_register(0x06, 0x0E)                      # PTDR | PDR | TDR
    # pressure 0x62 0xF3 0x80 = 101326.0 Pa, temperature 0x19 0x80 = 25.5 C
    c.set_register(0x01, 0x62, 0xF3, 0x80, 0x19, 0x80)
    return c


connection = make()
sensor = MPL3115A2Full(connection)
sensor.init()
check_true('init_no_bus_in_constructor', True)
check_true('init_reset_written', writes_to(connection, 0x26)[0][1] == 0x04)
check_true('init_pt_data_cfg', writes_to(connection, 0x13)[-1][1] == 0x07)
check_true('init_ctrl1_default', writes_to(connection, 0x26)[-1][1] == 0x38)

m = sensor.read()
check_true('read_pressure', abs(m['pressure_pa'] - 101326.0) < 1e-6)
check_true('read_temperature', abs(m['temperature_c'] - 25.5) < 1e-6)
check_true('read_sets_ost', writes_to(connection, 0x26)[-1][1] == 0x3A)

connection.set_register(0x04, 0xE7, 0x00)
check_true('negative_temperature', abs(sensor.read_temperature() + 25.0) < 1e-6)
connection.set_register(0x04, 0x19, 0x80)

# Altimeter: OUT_P 0x00 0x7B 0x30 -> 123 + 3/16
connection.set_register(0x01, 0x00, 0x7B, 0x30)
alt = sensor.read_altitude()
check_true('altitude_value', abs(alt - 123.1875) < 1e-6)
check_true('altitude_sets_alt_bit', writes_to(connection, 0x26)[-1][1] == 0xBA)
connection.set_register(0x01, 0xFF, 0x9C, 0x00)
check_true('altitude_negative', abs(sensor.read_altitude() + 100.0) < 1e-6)
connection.set_register(0x01, 0x62, 0xF3, 0x80)
check_true('read_switches_back_to_barometer', abs(sensor.read()['pressure_pa'] - 101326.0) < 1e-6)

# oversample
sensor.set_oversample(16)
check_true('oversample_get', sensor.get_oversample() == 16)
check_true('oversample_ctrl1', writes_to(connection, 0x26)[-1][1] == 0x20)
try:
    sensor.set_oversample(3)
    check_true('oversample_invalid', False)
except ValueError:
    check_true('oversample_invalid', True)
sensor.set_oversample(128)

# sea level pressure: 101326 / 2 = 50663 = 0xC5E7
sensor.set_sea_level_pressure(101326)
check_true('bar_in', writes_to(connection, 0x14)[-1] == bytes([0x14, 0xC5, 0xE7]))

# offsets
sensor.set_offsets(pressure_pa=-8.0, temperature_c=1.0, altitude_m=-3)
check_true('off_p', writes_to(connection, 0x2B)[-1][1] == 0xFE)
check_true('off_t', writes_to(connection, 0x2C)[-1][1] == 16)
check_true('off_h', writes_to(connection, 0x2D)[-1][1] == 0xFD)
try:
    sensor.set_offsets(pressure_pa=1000)
    check_true('offset_range', False)
except ValueError:
    check_true('offset_range', True)

# target/window (barometer: 2 Pa units)
sensor.set_target(100000, 200, temperature_c=-5, temperature_window=3)
check_true('p_tgt', writes_to(connection, 0x16)[-1] == bytes([0x16, 0xC3, 0x50]))
check_true('t_tgt', writes_to(connection, 0x18)[-1][1] == 0xFB)
check_true('p_wnd', writes_to(connection, 0x19)[-1] == bytes([0x19, 0x00, 0x64]))
check_true('t_wnd', writes_to(connection, 0x1B)[-1][1] == 3)

# continuous
sensor.start_continuous(2)
check_true('continuous_st', writes_to(connection, 0x27)[-1][1] & 0x0F == 2)
check_true('continuous_sbyb', writes_to(connection, 0x26)[-1][1] == 0x39)
check_true('data_ready', sensor.data_ready())
rc = sensor.read_continuous()
check_true('read_continuous', abs(rc['value'] - 101326.0) < 1e-6 and abs(rc['temperature_c'] - 25.5) < 1e-6)
sensor.stop_continuous()
check_true('stop_continuous', writes_to(connection, 0x26)[-1][1] == 0x38)

# delta
connection.set_register(0x07, 0x00, 0x00, 0x40, 0x00, 0x10)
dv, dt = sensor.read_delta()
check_true('delta', abs(dv - 1.0) < 1e-6 and abs(dt - 0.0625) < 1e-6)

# min/max
connection.set_register(0x1C, 0x62, 0xF3, 0x80, 0x19, 0x80)
mm = sensor.read_min_max()
check_true('min_max_p_min', abs(mm['p_min'] - 101326.0) < 1e-6)
sensor.clear_min_max()
check_true('clear_min_max', writes_to(connection, 0x1C)[-1] == bytes([0x1C, 0, 0, 0]))

# FIFO
sensor.fifo_setup(1, 16)
check_true('fifo_setup', writes_to(connection, 0x0F)[-1][1] == 0x50)
try:
    sensor.fifo_setup(2)
    check_true('fifo_switch_rejected', False)
except ValueError:
    check_true('fifo_switch_rejected', True)
connection.set_register(0x0D, 0xC2)
check_true('fifo_status', sensor.fifo_status() == (True, True, 2))
connection.set_register(0x0E, 0x62, 0xF3, 0x80, 0x19, 0x80)
samples = sensor.fifo_read()
check_true('fifo_read', len(samples) == 2 and abs(samples[0][0] - 101326.0) < 1e-6)
sensor.fifo_setup(0)

# interrupts
sensor.set_interrupt_pins(polarity1=True, open_drain1=True)
check_true('int_pins', writes_to(connection, 0x28)[-1][1] == 0x30)
sensor.enable_interrupt(MPL3115A2Full.SOURCE_TTH, 1)
check_true('int_enable', writes_to(connection, 0x29)[-1][1] & 0x04)
check_true('int_route_int1', writes_to(connection, 0x2A)[-1][1] & 0x04)
sensor.enable_interrupt(MPL3115A2Full.SOURCE_DRDY, 2)
check_true('int_route_int2', writes_to(connection, 0x2A)[-1][1] & 0x80 == 0)
sensor.disable_interrupt(MPL3115A2Full.SOURCE_TTH)
check_true('int_disable', writes_to(connection, 0x29)[-1][1] & 0x04 == 0)
connection.set_register(0x12, 0x84)
check_true('poll_interrupt', sensor.poll_interrupt() == 0x84)
try:
    sensor.enable_interrupt(1, 3)
    check_true('int_pin_invalid', False)
except ValueError:
    check_true('int_pin_invalid', True)

# misc
check_true('who_am_i', sensor.who_am_i() == 0xC4)
connection.set_register(0x11, 0x01)
check_true('is_active', sensor.is_active())
sensor.reset()
check_true('reset_restores_config', writes_to(connection, 0x26)[-1][1] == 0x38)

# wrong chip id
bad = I2CConnectionMock()
bad.set_register(0x0C, 0x00)
try:
    MPL3115A2Minimal(bad).init()
    check_true('init_bad_id', False)
except OSError:
    check_true('init_bad_id', True)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(1 if failed else 0)
