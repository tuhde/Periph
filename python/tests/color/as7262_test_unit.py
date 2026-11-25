import struct
import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.color.as7262 import AS7262Full, AS7262Minimal

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


def raises(fn, exc):
    try:
        fn()
    except exc:
        return True
    return False


class Sim(I2CConnectionMock):
    """AS7262 simulator: STATUS/WRITE/READ hardware registers over virtual registers."""

    def __init__(self):
        I2CConnectionMock.__init__(self)
        self.v = {0x00: 0x40, 0x01: 0x3E, 0x02: 0x41, 0x03: 0x21, 0x04: 0x08, 0x05: 0xFF, 0x06: 25, 0x07: 0}
        for i in range(12):
            self.v[0x08 + i] = 0
        for i in range(24):
            self.v[0x14 + i] = 0
        self.pending = None
        self.out = None
        self.vwrites = []
        self.stall = False

    def read_reg(self, reg, length):
        if reg == 0x00:
            if self.stall:
                return bytes([0x02])
            return bytes([(1 if self.out is not None else 0)])
        if reg == 0x02:
            val = self.out
            self.out = None
            return bytes([val or 0])
        return bytes(length)

    def write_reg(self, reg, data):
        b = data if isinstance(data, int) else data[0]
        self.writes.append(bytes([reg, b]))
        assert reg == 0x01
        if self.pending is not None:
            r = self.pending
            self.pending = None
            self.vwrites.append((r, b))
            if r == 0x04:
                if b & 0x80:
                    self.v.update({0x04: 0x08, 0x05: 0xFF, 0x07: 0})
                    return
                self.v[r] = b | (self.v[r] & 0x02)
                if (b >> 2) & 3 == 3:
                    self.v[r] |= 0x02  # conversion completes immediately
            else:
                self.v[r] = b
        elif b & 0x80:
            self.pending = b & 0x7F
        else:
            self.out = self.v.get(b, 0)
            if b == 0x04:
                pass
            if 0x08 <= b < 0x2C:
                self.v[0x04] &= ~0x02


def load_cal(sim, values):
    data = b''.join(struct.pack('>f', x) for x in values)
    for i, byte in enumerate(data):
        sim.v[0x14 + i] = byte


def make(full=True):
    c = Sim()
    return c, (AS7262Full if full else AS7262Minimal)(c)


c, s = make(False)
check_true('init_int_t', c.v[0x05] == 0x3B)
check_true('init_gain_16x', (c.v[0x04] >> 4) & 3 == 2)
check_true('init_leds_off', c.v[0x07] == 0)
check_true('init_reset_written', (0x04, 0x80) in c.vwrites)

load_cal(c, [1.0, 2.5, 3.0, 4.0, 5.0, 6.5])
d = s.read()
check_close('read_violet', d['violet'], 1.0)
check_close('read_blue', d['blue'], 2.5)
check_close('read_red', d['red'], 6.5)
check_true('read_starts_one_shot', (0x04, 0x2C) in c.vwrites)
check_true('temperature', s.temperature() == 25.0)

c, s = make()
for i in range(12):
    c.v[0x08 + i] = [0x01, 0x02][i % 2]
check_true('read_raw', s.read_raw()['violet'] == 0x0102)
check_true('read_channel_raw', s.read_channel(5, False) == 0x0102)
load_cal(c, [0, 0, 0, 0, 0, 7.25])
check_close('read_channel_cal', s.read_channel(5), 7.25)
check_true('read_channel_range', raises(lambda: s.read_channel(6), ValueError))

s.set_gain(64)
check_true('gain_64', (c.v[0x04] >> 4) & 3 == 3 and s.get_gain() == 64.0)
s.set_gain(3.7)
check_true('gain_3_7', s.get_gain() == 3.7)
s.set_gain(1)
check_true('gain_1x', s.get_gain() == 1.0)
check_true('gain_invalid', raises(lambda: s.set_gain(5), ValueError))
s.set_integration_time(50)
check_true('int_time_50', c.v[0x05] == 18 and abs(s.get_integration_time() - 50.4) < 1e-6)
check_true('int_time_low', raises(lambda: s.set_integration_time(1), ValueError))
check_true('int_time_high', raises(lambda: s.set_integration_time(800), ValueError))
s.set_mode(2)
check_true('mode_2', s.get_mode() == 2 and (c.v[0x04] >> 4) & 3 == 0)
s.set_continuous(0)
check_true('continuous_0', s.get_mode() == 0)
check_true('continuous_3_rejected', raises(lambda: s.set_continuous(3), ValueError))
s.set_interrupt(True)
check_true('interrupt_on', c.v[0x04] & 0x40)
s.set_interrupt(False)
check_true('interrupt_off', not c.v[0x04] & 0x40)

s.set_led(True, 25)
check_true('led_drv_25', c.v[0x07] == 0x18)
s.set_indicator_led(True, 4)
check_true('led_ind_4', c.v[0x07] == 0x18 | 0x05)
s.set_led(False, 100)
check_true('led_drv_off_keeps_ind', c.v[0x07] == 0x30 | 0x05)
check_true('led_drv_too_high', raises(lambda: s.set_led(True, 200), ValueError))
check_true('led_ind_too_high', raises(lambda: s.set_indicator_led(True, 9), ValueError))

check_true('hardware_version', s.hardware_version() == (0x40, 0x3E))
check_true('device_type', s.device_type() == 0x40)
check_true('firmware_version', s.firmware_version() == (2, 0x21 & 0 | ((0x21 & 0x0F) << 2 | 1), 1))
s.write_virtual(0x05, 0x10)
check_true('write_read_virtual', s.read_virtual(0x05) == 0x10)
s.soft_reset()
check_true('soft_reset_defaults', c.v[0x05] == 0xFF)

s.measure()
check_true('data_ready', s.data_ready())
check_true('poll_interrupt', s.poll_interrupt() == 1)

calls = []
s.on_interrupt(calls.append)
s._dispatch_int()
check_true('on_interrupt_dispatch', calls == [1])
s.off_interrupt()
check_true('off_interrupt', not c.v[0x04] & 0x40)

c.stall = True
check_true('handshake_timeout', raises(lambda: s.read_virtual(0x00), RuntimeError))

print('%d passed, %d failed' % (passed, failed))
sys.exit(1 if failed else 0)
