import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.light.si1133 import Si1133Minimal, Si1133Full

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


class Si1133Mock(I2CConnectionMock):
    """Simulates the command engine: RESPONSE0 counter, parameter table,
    FORCE packing HOSTOUT from per-channel sample values."""

    def __init__(self):
        super().__init__()
        self.part_id = 0x33
        self.params = [0] * 64
        self.counter = 0x0F
        self.error = 0
        self.resp1 = 0
        self.irq_enable = 0
        self.irq_status = 0
        self.hostout = bytes(26)
        self.samples = [0x012345, -5, 0xABCD, 0, 0, 0]   # per channel
        self.commands = []
        self.ignore_commands = False

    def resp0(self):
        if self.error:
            return 0x20 | 0x10 | (self.error & 0x0F)
        return 0x20 | self.counter

    def command(self, cmd, hostin=0):
        self.commands.append(cmd)
        if self.ignore_commands:
            return
        if cmd == 0x00:
            self.counter = 0
            self.error = 0
            return
        if cmd == 0x01:
            self.params = [0] * 64
            self.counter = 0x0F
            self.error = 0
            self.irq_enable = 0
            return
        if cmd & 0xC0 == 0x80:
            p = cmd & 0x3F
            if p > 0x2B:
                self.error = 0x11
                return
            self.params[p] = hostin
            self.resp1 = hostin
        elif cmd & 0xC0 == 0x40:
            self.resp1 = self.params[cmd & 0x3F]
        elif cmd == 0x11:
            out = bytearray()
            chan = self.params[1]
            for ch in range(6):
                if not chan & (1 << ch):
                    continue
                v = self.samples[ch]
                if self.params[0x04 + 4 * ch] & 0x40:
                    v &= 0xFFFFFF
                    out += bytes((v >> 16, (v >> 8) & 0xFF, v & 0xFF))
                else:
                    out += bytes(((v >> 8) & 0xFF, v & 0xFF))
            self.hostout = bytes(out) + bytes(26 - len(out))
            self.irq_status |= chan & self.irq_enable
        elif cmd not in (0x12, 0x13):
            self.error = 0x10
            return
        self.counter = (self.counter + 1) & 0x0F

    def write(self, data):
        data = bytes(data)
        self.writes.append(data)
        reg = data[0]
        if reg == 0x0A and len(data) >= 3:
            self.command(data[2], data[1])
        elif reg == 0x0B and len(data) >= 2:
            self.command(data[1])
        elif reg == 0x0F and len(data) >= 2:
            self.irq_enable = data[1]

    def write_read(self, data, n):
        reg = bytes(data)[0]
        self.writes.append(bytes(data))
        if reg == 0x00:
            block = bytes((self.part_id, 0x03, 0x10))
        elif reg == 0x10:
            block = bytes((self.resp1,))
        elif reg == 0x11:
            block = bytes((self.resp0(),))
        elif reg == 0x12:
            block = bytes((self.irq_status,))
            self.irq_status = 0
        elif reg >= 0x13:
            block = self.hostout[reg - 0x13:]
        else:
            block = b''
        return bytes(block[i] if i < len(block) else 0 for i in range(n))


def raises(exc, fn, *args):
    try:
        fn(*args)
        return False
    except exc:
        return True


c = Si1133Mock()
s = Si1133Full(c)
check_true('constructor_no_bus', c.writes == [])
s.init()
check_true('init_reset_sw', c.commands[0] == 0x01)
check_true('init_adcconfig0_uv', c.params[0x02] == 0x18)
check_true('init_adcsens0', c.params[0x03] == 0x09)
check_true('init_adcpost0_24bit', c.params[0x04] == 0x40)
check_true('init_adcconfig1_white', c.params[0x06] == 0x0B)
check_true('init_adcsens1', c.params[0x07] == 0x07)
check_true('init_adcpost1_24bit', c.params[0x08] == 0x40)
check_true('init_chan_list', c.params[0x01] == 0x03)
check_true('init_irq_enable', c.irq_enable == 0x3F)
check_true('param_set_one_write', bytes((0x0A, 0x03, 0x81)) in c.writes)

m = s.read()
check_true('read_uv', m['uv'] == 0x012345)
check_true('read_white_negative', m['white'] == -5)
check_true('read_force', c.commands[-1] == 0x11)
check_true('read_hostout_6', bytes((0x13,)) in c.writes)

# 16-bit channel 2
s.configure_channel(2, Si1133Full.MUX_LARGE_IR, hw_gain=3, sw_gain=2, decim_rate=1,
                    high_signal=True, bits24=False, postshift=1)
check_true('configure_adcconfig', c.params[0x0A] == 0x22)
check_true('configure_adcsens', c.params[0x0B] == 0xA3)
check_true('configure_adcpost', c.params[0x0C] == 0x08)
s.enable_channels(0x07)
check_true('enable_channels', c.params[0x01] == 0x07)
check_true('read_channels', s.read_channels() == [0x012345, -5, 0xABCD])
check_true('read_channels_no_force', s.read_channels(False) == [0x012345, -5, 0xABCD])
check_true('read_still_works', s.read() == {'uv': 0x012345, 'white': -5})
check_true('configure_invalid_channel', raises(ValueError, s.configure_channel, 6, 0))
check_true('configure_invalid_gain', raises(ValueError, s.configure_channel, 0, 0x18, 12))
check_true('enable_invalid', raises(ValueError, s.enable_channels, 0x40))

# scratch reads on channel 5
c.samples[5] = 1234
check_true('read_uv_scratch', s.read_uv() == 1234)
check_true('read_uv_mux', c.params[0x16] == 0x18 and c.params[0x17] == 0x09)
check_true('read_uv_restores', c.params[0x01] == 0x07)
check_true('read_white_scratch', s.read_white() == 1234 and c.params[0x16] == 0x0B)
check_true('read_ir_scratch', s.read_ir(Si1133Full.MUX_MEDIUM_IR) == 1234 and c.params[0x16] == 0x01)
check_true('read_ir_default', s.read_ir() == 1234 and c.params[0x16] == 0x00)
check_true('read_ir_invalid', raises(ValueError, s.read_ir, 0x0B))

s.force()
check_true('force', c.commands[-1] == 0x11)

# autonomous
s.start_autonomous(1.0, (2, 3, 4))
check_true('measrate', (c.params[0x1A] << 8) | c.params[0x1B] == 1250)
check_true('meascount', c.params[0x1C:0x1F] == [2, 3, 4])
check_true('counter_index', [c.params[0x05 + 4 * ch] for ch in range(3)] == [0x40] * 3)
check_true('start', c.commands[-1] == 0x13)
s.set_channel_counter(2, 3)
check_true('set_channel_counter', c.params[0x0D] == 0xC0)
s.pause()
check_true('pause', c.commands[-1] == 0x12)
check_true('autonomous_invalid', raises(ValueError, s.start_autonomous, 0, (1, 1, 1)))

s.set_burst(4)
check_true('burst', c.params[0x2B] == 0x84)
s.set_burst(0)
check_true('burst_off', c.params[0x2B] == 0)
check_true('burst_invalid', raises(ValueError, s.set_burst, 128))

s.set_threshold(1, 0x1234)
check_true('threshold', c.params[0x27] == 0x12 and c.params[0x28] == 0x34)
s.set_channel_threshold(0, 2)
check_true('channel_threshold', c.params[0x04] == 0x42)
check_true('threshold_invalid', raises(ValueError, s.set_threshold, 3, 0))

s.write_parameter(0x1C, 0x55)
check_true('write_parameter', c.params[0x1C] == 0x55)
check_true('read_parameter', s.read_parameter(0x1C) == 0x55)
check_true('read_parameter_query', c.commands[-1] == 0x5C)

state, err, counter = s.read_response()
check_true('read_response', state == 0x20 and not err and counter == c.counter)

# command error: invalid parameter address
check_true('param_error', raises(OSError, s.write_parameter, 0x30, 1))
check_true('param_error_cleared', c.commands[-1] == 0x00 and c.error == 0)

c.irq_status = 0x05
check_true('data_ready', s.data_ready() == 0x05)
check_true('data_ready_cleared', s.data_ready() == 0)

s.disable_interrupt(Si1133Full.SOURCE_CHANNEL1)
check_true('disable_interrupt', c.irq_enable == 0x3D)
s.enable_interrupt(Si1133Full.SOURCE_CHANNEL1)
check_true('enable_interrupt', c.irq_enable == 0x3F)
c.irq_status = 0x02
check_true('poll_interrupt', s.poll_interrupt() == 0x02)

events = []


class FakePin:
    FALLING = 2

    def __init__(self):
        self.handler = None

    def on_edge(self, handler, edge):
        self.handler = handler

    def off_edge(self, handler):
        self.handler = None


pin = FakePin()
s.on_interrupt(events.append, int_pin=pin)
c.irq_status = 0x01
pin.handler(pin)
check_true('on_interrupt', events == [0x01])
s.off_interrupt()
check_true('off_interrupt', pin.handler is None)

check_true('read_id', s.read_id() == (0x33, 0x03, 0x10))

s.set_i2c_address(0x30)
check_true('set_i2c_address', c.params[0x00] == 0x30)
check_true('set_i2c_address_invalid', raises(ValueError, s.set_i2c_address, 0x80))

s.reset()
check_true('reset_defaults', c.params[0x01] == 0x03 and c.params[0x02] == 0x18)
check_true('reset_read', s.read() == {'uv': 0x012345, 'white': -5})

# timeouts / identity
stuck = Si1133Mock()
s2 = Si1133Minimal(stuck)
s2.init()
stuck.ignore_commands = True
check_true('command_timeout', raises(OSError, s2.read))

bad = Si1133Mock()
bad.part_id = 0x32
check_true('init_wrong_part', raises(OSError, Si1133Minimal(bad).init))

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(1 if failed else 0)
