import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.gas.ccs811 import CCS811Minimal, CCS811Full

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


def near(a, b, tol=1e-6):
    return abs(a - b) < tol


# ALG_RESULT_DATA: eCO2 500 ppm, TVOC 15 ppb, STATUS 0x98, ERROR_ID 0
RESULT = bytes((0x01, 0xF4, 0x00, 0x0F, 0x98, 0x00, 0x8C, 0x88))
RAW = bytes((0x8C, 0x88))           # 35 uA, ADC 136 -> 0.2193548387 V
NTC = bytes((0x06, 0x40, 0x03, 0x20))  # 1600 mV, 800 mV


class Ccs811Mock(I2CConnectionMock):
    """Each register is its own mailbox; STATUS tracks FW_MODE and DATA_READY."""

    def __init__(self):
        super().__init__()
        self.mail = {
            0x20: b'\x81', 0x21: b'\x12', 0x23: b'\x10\x00', 0x24: b'\x11\x02',
            0x02: RESULT, 0x03: RAW, 0x06: NTC, 0x11: b'\x84\x7B', 0xE0: b'\x00',
        }
        self.app_valid = True
        self.fw_mode = False
        self.data_ready = True
        self.ready_after_start = True

    def status_byte(self):
        return ((0x80 if self.fw_mode else 0) | (0x10 if self.app_valid else 0)
                | (0x08 if self.data_ready else 0))

    def write(self, data):
        data = bytes(data)
        self.writes.append(data)
        if data[0] == 0xF4 and len(data) == 1 and self.ready_after_start:
            self.fw_mode = True
        elif data[0] == 0xFF:
            self.fw_mode = False
        elif len(data) >= 2:
            self.mail[data[0]] = data[1:]

    def write_read(self, data, n):
        reg = bytes(data)[0]
        self.writes.append(bytes(data))
        if reg == 0x00:
            return bytes((self.status_byte(),))
        value = self.mail.get(reg, b'')
        if reg == 0x02:
            self.data_ready = False
        return bytes(value[i] if i < len(value) else 0 for i in range(n))


def writes_to(c, reg):
    return [w for w in c.writes if w[0] == reg and len(w) >= 2]


def last(c, reg):
    w = writes_to(c, reg)
    return w[-1][1:] if w else None


c = Ccs811Mock()
s = CCS811Full(c)
check_true('constructor_no_bus', c.writes == [])
s.init()
check_true('init_app_start', bytes((0xF4,)) in c.writes)
check_true('init_mode_1', last(c, 0x01) == b'\x10')

m = s.read()
check_true('read_eco2', m['eco2_ppm'] == 500)
check_true('read_tvoc', m['tvoc_ppb'] == 15)
check_true('read_length_5', any(w == b'\x02' for w in c.writes))
check_true('read_clears_ready', not c.data_ready)
c.data_ready = True
check_true('read_eco2_single', s.read_eco2() == 500)
c.data_ready = True
check_true('read_tvoc_single', s.read_tvoc() == 15)
check_true('data_ready_false', s.data_ready() is False)
c.data_ready = True
check_true('data_ready_true', s.data_ready() is True)

cur, volt = s.read_raw()
check_true('read_raw_current', cur == 35)
check_true('read_raw_voltage', near(volt, 136 * 1.65 / 1023))

# drive mode keeps interrupt bits
s.enable_interrupt(CCS811Full.SOURCE_DATA_READY)
check_true('int_enable_data_ready', last(c, 0x01) == b'\x18')
s.set_mode(2)
check_true('set_mode_keeps_int', last(c, 0x01) == b'\x28')
s.enable_interrupt(CCS811Full.SOURCE_THRESHOLD)
check_true('int_enable_threshold', last(c, 0x01) == b'\x2C')
s.disable_interrupt(CCS811Full.SOURCE_THRESHOLD)
check_true('int_disable_threshold', last(c, 0x01) == b'\x20')
s.enable_interrupt(CCS811Full.SOURCE_THRESHOLD)
s.disable_interrupt(CCS811Full.SOURCE_DATA_READY)
check_true('int_disable_data_ready', last(c, 0x01) == b'\x24')
s.disable_interrupt(CCS811Full.SOURCE_THRESHOLD)
s.set_mode(4)
check_true('set_mode_4', last(c, 0x01) == b'\x40')
s.set_mode(0)
check_true('set_mode_idle', last(c, 0x01) == b'\x00')
for bad in (-1, 5):
    try:
        s.set_mode(bad)
        check_true('set_mode_invalid_%d' % bad, False)
    except ValueError:
        check_true('set_mode_invalid_%d' % bad, True)
s.set_mode(1)

s.set_environment(48.5, 22.5)
check_true('set_environment', last(c, 0x05) == b'\x61\x00\x5F\x00')
s.set_environment(50, 25)
check_true('set_environment_default', last(c, 0x05) == b'\x64\x00\x64\x00')
for h, t in ((-1, 25), (101, 25), (50, -26), (50, 103)):
    try:
        s.set_environment(h, t)
        check_true('set_environment_invalid', False)
    except ValueError:
        check_true('set_environment_invalid', True)

check_true('read_ntc', s.read_ntc() == (1600, 800))
check_true('ntc_resistance', near(s.ntc_resistance(100000), 50000.0))
c.mail[0x06] = b'\x00\x00\x03\x20'
try:
    s.ntc_resistance(100000)
    check_true('ntc_resistance_no_ref', False)
except OSError:
    check_true('ntc_resistance_no_ref', True)
c.mail[0x06] = NTC

s.set_thresholds(1000, 2000, 50)
check_true('set_thresholds', last(c, 0x10) == b'\x03\xE8\x07\xD0\x32')
s.set_thresholds()
check_true('set_thresholds_default', last(c, 0x10) == b'\x05\xDC\x09\xC4\x32')
for bad in ((2000, 1000, 50), (1000, 2000, 256), (1000, 70000, 50)):
    try:
        s.set_thresholds(*bad)
        check_true('set_thresholds_invalid', False)
    except ValueError:
        check_true('set_thresholds_invalid', True)

check_true('get_baseline', s.get_baseline() == 0x847B)
s.set_baseline(0x1234)
check_true('set_baseline', last(c, 0x11) == b'\x12\x34')
check_true('get_baseline_roundtrip', s.get_baseline() == 0x1234)
try:
    s.set_baseline(0x10000)
    check_true('set_baseline_invalid', False)
except ValueError:
    check_true('set_baseline_invalid', True)

c.data_ready = False
check_true('status', s.status() == 0x90)
c.mail[0xE0] = b'\x10'
check_true('error', s.error() == 0x10)
c.mail[0xE0] = b'\x00'
check_true('hardware_version', s.hardware_version() == (0x81, 0x12))
check_true('firmware_version', s.firmware_version() == {'boot': (1, 0, 0), 'app': (1, 1, 2)})

# interrupts
c.data_ready = True
check_true('poll_interrupt', s.poll_interrupt() == 0x98)
check_true('poll_interrupt_keeps_ready', c.data_ready)


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
s.on_interrupt(seen.append, int_pin=pin)
check_true('on_interrupt_falling_edge', pin.edge == FakePin.FALLING)
pin.handler(pin)
check_true('on_interrupt_dispatch', seen == [0x98])
c.data_ready = False
pin.handler(pin)
check_true('on_interrupt_no_spurious', seen == [0x98])
s.off_interrupt()
check_true('off_interrupt', pin.handler is None)

# error flag in the result
c.data_ready = True
c.mail[0x02] = bytes((0x01, 0xF4, 0x00, 0x0F, 0x99, 0x08))
c.mail[0xE0] = b'\x08'
try:
    s.read()
    check_true('read_error_flag', False)
except OSError as e:
    check_true('read_error_flag', '0x08' in str(e))
c.mail[0x02] = RESULT
c.mail[0xE0] = b'\x00'

# soft reset re-starts the application
n = len(c.writes)
s.set_mode(3)
s.soft_reset()
check_true('soft_reset_key', b'\xFF\x11\xE5\x72\x8A' in c.writes[n:])
check_true('soft_reset_app_start', bytes((0xF4,)) in c.writes[n:])
check_true('soft_reset_mode_1', last(c, 0x01) == b'\x10')
c.data_ready = True
check_true('read_after_reset', s.read()['eco2_ppm'] == 500)

# Minimal class alone
mc = Ccs811Mock()
mn = CCS811Minimal(mc)
mn.init()
mm = mn.read()
check_true('minimal_read', mm == {'eco2_ppm': 500, 'tvoc_ppb': 15})

# already in application mode: no APP_START
ac = Ccs811Mock()
ac.fw_mode = True
CCS811Minimal(ac).init()
check_true('init_app_running', bytes((0xF4,)) not in ac.writes)

# wrong HW_ID
bad = Ccs811Mock()
bad.mail[0x20] = b'\x55'
try:
    CCS811Minimal(bad).init()
    check_true('init_bad_id', False)
except OSError:
    check_true('init_bad_id', True)

# no application firmware
noapp = Ccs811Mock()
noapp.app_valid = False
try:
    CCS811Minimal(noapp).init()
    check_true('init_no_app', False)
except OSError:
    check_true('init_no_app', True)

# APP_START ignored
stuck = Ccs811Mock()
stuck.ready_after_start = False
try:
    CCS811Minimal(stuck).init()
    check_true('init_app_start_failed', False)
except OSError:
    check_true('init_app_start_failed', True)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(1 if failed else 0)
