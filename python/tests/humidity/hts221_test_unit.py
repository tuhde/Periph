import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.humidity.hts221 import HTS221Full, HTS221Minimal

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


class ChipMock(I2CConnectionMock):
    """Mock that clears CTRL_REG2.BOOT like the real chip."""

    def write(self, data):
        super().write(data)
        if len(data) == 2 and data[0] == 0x21:
            self.registers[0x21] = data[1] & ~0x80


def make(mock_cls=I2CConnectionMock):
    c = mock_cls()
    # Calibration block at 0xB0 (sub-address with auto-increment):
    # H0_rH_x2=40, H1_rH_x2=80, T0_x8=80, T1_x8=160, msb=0,
    # H0_T0_OUT=0x4000, H1_T0_OUT=0x6000, T0_OUT=300, T1_OUT=500
    c.set_register(0xB0, 40, 80, 80, 160, 0, 0,
                   0x00, 0x40, 0, 0, 0x00, 0x60,
                   300 & 0xFF, 300 >> 8, 500 & 0xFF, 500 >> 8)
    c.set_register(0x0F, 0xBC)
    c.set_register(0x27, 0x03)
    # Burst 0xA8: H_OUT = 0x5000 (30 %rH), T_OUT = 400 (15.0 degC)
    c.set_register(0xA8, 0x00, 0x50, 400 & 0xFF, 400 >> 8)
    return c


c = make()
s = HTS221Minimal(c)
check_true('init_writes_ctrl_reg1', c.writes[-1] == bytes([0x20, 0x84]))
check_true('init_reads_calibration', bytes([0xB0]) in c.writes)
m = s.read()
check_true('read_humidity', near(m['humidity_pct'], 30.0))
check_true('read_temperature', near(m['temperature_c'], 15.0))
check_true('read_starts_one_shot', bytes([0x21, 0x01]) in c.writes)

# clamping
c4 = make()
c4.set_register(0xB6, 0x00, 0x00, 0, 0, 0x00, 0x10)  # H0_T0_OUT=0, H1_T0_OUT=0x1000
s4 = HTS221Minimal(c4)
c4.set_register(0xA8, 0xFF, 0x7F, 0, 0)
check_true('humidity_clamped_100', near(s4.read()['humidity_pct'], 100.0))
c.set_register(0xA8, 0x00, 0x80, 0, 0)
check_true('humidity_clamped_0', near(s.read()['humidity_pct'], 0.0))
c.set_register(0xA8, 0x00, 0x50, 400 & 0xFF, 400 >> 8)

# timeout
c2 = make()
c2.set_register(0x27, 0x00)
s2 = HTS221Minimal(c2)
try:
    s2.read()
    check_true('read_timeout', False)
except OSError:
    check_true('read_timeout', True)

# calibration with T bits 9:8: T0 = 0x1FF/8? msb=0x05 -> T0 bits=1, T1 bits=1
c3 = make()
c3.set_register(0xB5, 0x05)
f3 = HTS221Full(c3)
cal3 = f3.read_calibration()
check_true('calib_t0_msb', near(cal3['T0_degC'], (256 + 80) / 8.0))
check_true('calib_t1_msb', near(cal3['T1_degC'], (256 + 160) / 8.0))
c3.set_register(0xB5, 0x0C)
f3 = HTS221Full(c3)
check_true('calib_t1_bit9_8', near(f3.read_calibration()['T1_degC'], (0x300 + 160) / 8.0))

c = make(ChipMock)
f = HTS221Full(c)
check_true('inherits_minimal', isinstance(f, HTS221Minimal))
check_true('read_humidity_full', near(f.read_humidity(), 30.0))
check_true('read_temperature_full', near(f.read_temperature(), 15.0))
check_true('read_raw_humidity', f.read_raw_humidity() == 0x5000)
check_true('read_raw_temperature', f.read_raw_temperature() == 400)
check_true('read_continuous', near(f.read_continuous()['temperature_c'], 15.0))
cal = f.read_calibration()
check_true('calib_h0', near(cal['H0_rH'], 20.0) and near(cal['H1_rH'], 40.0))
check_true('calib_t', near(cal['T0_degC'], 10.0) and near(cal['T1_degC'], 20.0))
check_true('calib_out', cal['H0_T0_OUT'] == 0x4000 and cal['H1_T0_OUT'] == 0x6000
           and cal['T0_OUT'] == 300 and cal['T1_OUT'] == 500)
check_true('who_am_i', f.who_am_i() == 0xBC)
check_true('status', f.status() == 0x03)
check_true('data_ready', f.data_ready())
check_true('poll_interrupt', f.poll_interrupt() == 0x03)

# negative raw (sign extension)
c.set_register(0xA8, 0x00, 0xF0, 0x00, 0x00)
check_true('raw_negative', f.read_raw_humidity() == -4096)
c.set_register(0xA8, 0x00, 0x50, 400 & 0xFF, 400 >> 8)

# output data rate
f.set_output_data_rate(12.5)
check_true('odr_12_5', c.registers[0x20] == 0x87)
check_true('get_odr', near(f.get_output_data_rate(), 12.5))
f.set_output_data_rate(7)
check_true('odr_7', c.registers[0x20] == 0x86)
f.set_output_data_rate(1)
check_true('odr_1', c.registers[0x20] == 0x85)
f.set_output_data_rate(0)
check_true('odr_0', c.registers[0x20] == 0x84)
try:
    f.set_output_data_rate(5)
    check_true('odr_invalid', False)
except ValueError:
    check_true('odr_invalid', True)

# averaging: temp 256 -> 7, hum 512 -> 7
f.set_averaging(256, 512)
check_true('averaging_max', c.registers[0x10] == 0x3F)
check_true('get_averaging', f.get_averaging() == (256, 512))
f.set_averaging(2, 4)
check_true('averaging_min', c.registers[0x10] == 0x00)
f.set_averaging(16, 32)
check_true('averaging_default', c.registers[0x10] == 0x1B)
check_true('get_averaging_default', f.get_averaging() == (16, 32))
for bad in ((3, 32), (16, 2), (1, 32), (16, 1024)):
    try:
        f.set_averaging(*bad)
        check_true('averaging_invalid_%d_%d' % bad, False)
    except ValueError:
        check_true('averaging_invalid_%d_%d' % bad, True)

# BDU / power
f.set_block_data_update(False)
check_true('bdu_off', c.registers[0x20] == 0x80)
f.set_block_data_update(True)
check_true('bdu_on', c.registers[0x20] == 0x84)
f.power_down()
check_true('power_down', c.registers[0x20] == 0x04)
f.power_up()
check_true('power_up', c.registers[0x20] == 0x84)

# heater
f.set_heater(True)
check_true('heater_on', c.registers[0x21] & 0x02 and f.is_heater_on())
f.set_heater(False)
check_true('heater_off', not f.is_heater_on())

# DRDY
f.configure_drdy(True)
check_true('drdy_default', c.registers[0x22] == 0x04)
f.configure_drdy(True, active_high=False, open_drain=True)
check_true('drdy_low_od', c.registers[0x22] == 0xC4)
f.configure_drdy(False)
check_true('drdy_off', c.registers[0x22] == 0x00)

# reboot re-reads calibration
c.set_register(0xB0, 60)
f.reboot()
check_true('reboot_sets_boot', any(w[0] == 0x21 and len(w) == 2 and w[1] & 0x80 for w in c.writes))
check_true('reboot_rereads_calibration', near(f.read_calibration()['H0_rH'], 30.0))

# dew point: 25 degC, 60 %rH -> about 16.7 degC
c.set_register(0xB0, 40)
f.reboot()
# T = 400 raw => 15 degC, H = 30 %rH: Magnus
import math
g = math.log(0.30) + 17.62 * 15.0 / (243.12 + 15.0)
expected = 243.12 * g / (17.62 - g)
check_true('dew_point', near(f.dew_point(), expected, 1e-9))

# interrupt (polling fallback not started without _thread timing -> pin path)
class Pin:
    RISING = 1

    def __init__(self):
        self.handler = None
        self.trigger = None

    def on_edge(self, handler, trigger=2):
        self.handler = handler
        self.trigger = trigger

    def off_edge(self, handler):
        if self.handler is handler:
            self.handler = None


pin = Pin()
got = []
f.on_interrupt(got.append, int_pin=pin)
check_true('interrupt_drdy_enabled', c.registers[0x22] == 0x04)
check_true('interrupt_rising', pin.trigger == 1)
pin.handler()
check_true('interrupt_callback', got == [0x03])
f.off_interrupt()
check_true('interrupt_unsubscribed', pin.handler is None)
check_true('interrupt_drdy_disabled', c.registers[0x22] == 0x00)

print('===DONE: %d passed, %d failed===' % (passed, failed))
sys.exit(1 if failed else 0)
