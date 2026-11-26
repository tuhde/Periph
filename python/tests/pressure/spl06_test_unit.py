import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.pressure.spl06 import SPL06Minimal, SPL06Full

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


def near(a, b, tol=1e-3):
    return abs(a - b) < tol


# Coefficients c0=204 c1=-261 c00=80469 c10=-54769 c01=-2659 c11=1258 c20=-10529 c21=94 c30=-1350
COEF = (0x0C, 0xCE, 0xFB, 0x13, 0xA5, 0x5F, 0x2A, 0x0F, 0xF5,
        0x9D, 0x04, 0xEA, 0xD6, 0xDF, 0x00, 0x5E, 0xFA, 0xBA)
TRAW = (0x02, 0x5C, 0x28)   # 154664
PRAW = (0xFE, 0x94, 0xB7)   # -93001
T_EXP = 25.0054779053       # 1x temperature
P_EXP = 98263.8611522177    # 16x pressure, 1x temperature


class Spl06Mock(I2CConnectionMock):
    """MEAS_CFG keeps its read-only ready flags on write; PRS_B2..0 pops FIFO entries when queued."""

    def __init__(self):
        super().__init__()
        self.flags = 0xF0
        self.fifo = []

    def write(self, data):
        super().write(data)
        data = bytes(data)
        if len(data) >= 2 and data[0] == 0x08:
            self.registers[0x08] = (data[1] & 0x07) | self.flags

    def write_read(self, data, n):
        reg = bytes(data)[0]
        if reg == 0x0B and self.fifo is not None:
            self.registers[0x0B] = 0x00 if self.fifo else 0x01
        if reg == 0x00 and n == 3 and self.fifo:
            self.writes.append(bytes(data))
            return bytes(self.fifo.pop(0))
        return super().write_read(data, n)


def make():
    c = Spl06Mock()
    c.set_register(0x0D, 0x10)
    c.set_register(0x08, 0xF0)
    c.set_register(0x10, *COEF)
    c.set_register(0x00, *(PRAW + TRAW))
    c.fifo = None
    return c


connection = make()
sensor = SPL06Full(connection)
check_true('constructor_no_bus', connection.writes == [])
sensor.init()
check_true('init_tmp_cfg', writes_to(connection, 0x07)[-1][1] == 0x80)
check_true('init_prs_cfg', writes_to(connection, 0x06)[-1][1] == 0x04)
check_true('init_cfg_reg', writes_to(connection, 0x09)[-1][1] == 0x04)

co = sensor.coefficients()
check_true('coef_c0', co['c0'] == 204)
check_true('coef_c1', co['c1'] == -261)
check_true('coef_c00', co['c00'] == 80469)
check_true('coef_c10', co['c10'] == -54769)
check_true('coef_c01', co['c01'] == -2659)
check_true('coef_c11', co['c11'] == 1258)
check_true('coef_c20', co['c20'] == -10529)
check_true('coef_c21', co['c21'] == 94)
check_true('coef_c30', co['c30'] == -1350)

m = sensor.read()
check_true('read_temperature', near(m['temperature_c'], T_EXP))
check_true('read_pressure', near(m['pressure_pa'], P_EXP))
meas = [w[1] for w in writes_to(connection, 0x08)]
check_true('read_temperature_then_pressure', meas[-2:] == [0x02, 0x01])
check_true('read_pressure_single', near(sensor.read_pressure(), P_EXP))
check_true('read_temperature_single', near(sensor.read_temperature(), T_EXP))
check_true('read_temperature_command', writes_to(connection, 0x08)[-1][1] == 0x02)
check_true('read_raw', sensor.read_raw() == (-93001, 154664))

# compensate is pure computation
r = sensor.compensate(-93001, 154664, 16, 1)
check_true('compensate', near(r['pressure_pa'], P_EXP) and near(r['temperature_c'], T_EXP))
r = sensor.compensate(-93001, 154664, 8, 2)
check_true('compensate_other_scale', near(r['pressure_pa'], 80852.2826419888)
           and near(r['temperature_c'], 76.3351593018))
try:
    sensor.compensate(0, 0, 3, 1)
    check_true('compensate_invalid', False)
except ValueError:
    check_true('compensate_invalid', True)

check_true('altitude', near(sensor.altitude(), 258.0291543011, 1e-2))
check_true('altitude_reference', near(sensor.altitude(98263.8611522177), 0.0, 1e-6))

# configuration
sensor.set_pressure_config(128, 8)
check_true('prs_cfg', writes_to(connection, 0x06)[-1][1] == 0x37)
check_true('p_shift_set', writes_to(connection, 0x09)[-1][1] & 0x04)
sensor.set_pressure_config(8, 1)
check_true('prs_cfg_8x', writes_to(connection, 0x06)[-1][1] == 0x03)
check_true('p_shift_cleared', writes_to(connection, 0x09)[-1][1] & 0x04 == 0)
sensor.set_temperature_config(32, 4)
check_true('tmp_cfg', writes_to(connection, 0x07)[-1][1] == 0xA5)
check_true('t_shift_set', writes_to(connection, 0x09)[-1][1] & 0x08)
sensor.set_temperature_config(2, 1)
check_true('tmp_cfg_ext_kept', writes_to(connection, 0x07)[-1][1] == 0x81)
check_true('t_shift_cleared', writes_to(connection, 0x09)[-1][1] & 0x08 == 0)
check_true('read_uses_new_scale', near(sensor.read()['pressure_pa'], 80852.2826419888))
for bad in ((3, 1), (16, 3), (256, 1)):
    try:
        sensor.set_pressure_config(*bad)
        check_true('pressure_config_invalid_%d_%d' % bad, False)
    except ValueError:
        check_true('pressure_config_invalid_%d_%d' % bad, True)
try:
    sensor.set_temperature_config(0)
    check_true('temperature_config_invalid', False)
except ValueError:
    check_true('temperature_config_invalid', True)
sensor.set_pressure_config()
sensor.set_temperature_config()

# background mode
sensor.start_continuous()
check_true('continuous_both', writes_to(connection, 0x08)[-1][1] == 0x07)
sensor.start_continuous(pressure=True, temperature=False)
check_true('continuous_pressure', writes_to(connection, 0x08)[-1][1] == 0x05)
sensor.start_continuous(pressure=False, temperature=True)
check_true('continuous_temperature', writes_to(connection, 0x08)[-1][1] == 0x06)
try:
    sensor.start_continuous(False, False)
    check_true('continuous_none_rejected', False)
except ValueError:
    check_true('continuous_none_rejected', True)
sensor.start_continuous()
n = len(writes_to(connection, 0x08))
rc = sensor.read_continuous()
check_true('read_continuous', near(rc['pressure_pa'], P_EXP) and near(rc['temperature_c'], T_EXP))
check_true('read_continuous_no_command', len(writes_to(connection, 0x08)) == n)
check_true('read_in_background', near(sensor.read()['pressure_pa'], P_EXP)
           and len(writes_to(connection, 0x08)) == n)
check_true('data_ready', sensor.data_ready() == (True, True))
connection.flags = 0xD0
connection.registers[0x08] = 0xD7
check_true('data_ready_pressure_only', sensor.data_ready() == (True, False))
connection.flags = 0xF0
connection.registers[0x08] = 0xF7
sensor.stop()
check_true('stop', writes_to(connection, 0x08)[-1][1] == 0x00)

# FIFO
sensor.enable_fifo()
check_true('fifo_enable', writes_to(connection, 0x09)[-1][1] & 0x02)
connection.fifo = [TRAW, PRAW]
entries = sensor.read_fifo()
check_true('fifo_count', len(entries) == 2)
check_true('fifo_temperature', entries[0][0] == 'temperature' and near(entries[0][1], T_EXP))
check_true('fifo_pressure', entries[1][0] == 'pressure' and near(entries[1][1], 98264.0472093194))
check_true('fifo_empty_read', sensor.read_fifo() == [])
connection.fifo = None
connection.set_register(0x0B, 0x02)
check_true('fifo_status_full', sensor.fifo_status() == (False, True))
connection.set_register(0x0B, 0x01)
check_true('fifo_status_empty', sensor.fifo_status() == (True, False))
sensor.flush_fifo()
check_true('fifo_flush', writes_to(connection, 0x0C)[-1][1] == 0x80)
sensor.disable_fifo()
check_true('fifo_disable', writes_to(connection, 0x09)[-1][1] & 0x02 == 0)

# interrupts
sensor.enable_interrupt(SPL06Full.SOURCE_PRESSURE)
check_true('int_enable_pressure', writes_to(connection, 0x09)[-1][1] & 0x20)
sensor.enable_interrupt(SPL06Full.SOURCE_TEMPERATURE | SPL06Full.SOURCE_FIFO_FULL)
check_true('int_enable_combined', writes_to(connection, 0x09)[-1][1] & 0x70 == 0x70)
check_true('int_keeps_p_shift', writes_to(connection, 0x09)[-1][1] & 0x04)
sensor.disable_interrupt(SPL06Full.SOURCE_PRESSURE | SPL06Full.SOURCE_FIFO_FULL)
check_true('int_disable', writes_to(connection, 0x09)[-1][1] & 0x70 == 0x10)
connection.set_register(0x0A, 0xFA)
check_true('poll_interrupt_masked', sensor.poll_interrupt() == 0x02)


class FakePin:
    FALLING = 2
    RISING = 1

    def __init__(self):
        self.handler = None
        self.edge = None

    def on_edge(self, handler, edge):
        self.handler = handler
        self.edge = edge

    def off_edge(self, handler):
        if handler is self.handler:
            self.handler = None


pin = FakePin()
seen = []
sensor.on_interrupt(seen.append, int_pin=pin)
check_true('on_interrupt_falling_edge', pin.edge == FakePin.FALLING)
connection.set_register(0x0A, 0x01)
pin.handler(pin)
check_true('on_interrupt_dispatch', seen == [0x01])
connection.set_register(0x0A, 0x00)
pin.handler(pin)
check_true('on_interrupt_no_spurious', seen == [0x01])
sensor.off_interrupt()
check_true('off_interrupt', pin.handler is None)
sensor.disable_interrupt(SPL06Full.SOURCE_TEMPERATURE)

# reset restores defaults
sensor.set_pressure_config(64, 2)
sensor.reset()
check_true('reset_command', writes_to(connection, 0x0C)[-1][1] == 0x09)
check_true('reset_defaults', writes_to(connection, 0x06)[-1][1] == 0x04
           and writes_to(connection, 0x07)[-1][1] == 0x80
           and writes_to(connection, 0x09)[-1][1] == 0x04)
check_true('read_after_reset', near(sensor.read()['pressure_pa'], P_EXP))

# Minimal class alone
minimal = SPL06Minimal(make())
minimal.init()
mm = minimal.read()
check_true('minimal_read', near(mm['pressure_pa'], P_EXP) and near(mm['temperature_c'], T_EXP))

# wrong chip id
bad = make()
bad.set_register(0x0D, 0x58)
try:
    SPL06Minimal(bad).init()
    check_true('init_bad_id', False)
except OSError:
    check_true('init_bad_id', True)

# coefficients never ready
slow = make()
slow.set_register(0x08, 0x40)
try:
    SPL06Minimal(slow).init()
    check_true('init_coef_not_ready', False)
except OSError:
    check_true('init_coef_not_ready', True)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(1 if failed else 0)
