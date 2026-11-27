import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.light.si1145 import Si1145Minimal, Si1145Full

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


class Si1145Sim(I2CConnectionMock):
    """Simulates the command engine: RESPONSE counter, parameter RAM,
    forced measurements filling the data registers and IRQ_STATUS."""

    def __init__(self, part_id=0x45, seq_id=0x08):
        super().__init__()
        self.regs = [0] * 0x40
        self.regs[0x00] = part_id
        self.regs[0x01] = 0x00
        self.regs[0x02] = seq_id
        self.params = [0] * 32
        self.params[0x00] = 0x60
        self.params[0x02] = 0x21
        self.params[0x0C] = 0x04
        self.params[0x0F] = 0x65
        self.samples = {'vis': 260, 'ir': 300, 'ps1': 1500, 'ps2': 1600, 'ps3': 1700,
                        'uv': 345, 'temp': 0x1111, 'vdd': 0x2222}
        self.cal = list(range(0x10, 0x1C))
        self.commands = []
        self.ignore_commands = False
        self.fail_param = None

    def irq_status(self):
        return self.regs[0x21]

    def command(self, cmd):
        self.commands.append(cmd)
        if self.ignore_commands:
            return
        resp = self.regs[0x20]
        if cmd == 0x00:
            self.regs[0x20] = 0
            return
        if cmd == 0x01:
            self.regs[0x20] = 0
            self.regs[0x07] = 0
            return
        if resp & 0x80:
            return                       # commands ignored until NOP
        if cmd & 0xE0 == 0xA0:
            off = cmd & 0x1F
            if off == self.fail_param:
                self.regs[0x20] = 0x80
                return
            self.params[off] = self.regs[0x17]
            self.regs[0x2E] = self.regs[0x17]
        elif cmd & 0xE0 == 0x80:
            self.regs[0x2E] = self.params[cmd & 0x1F]
        elif cmd in (0x05, 0x06, 0x07):
            self.measure(cmd in (0x05, 0x07), cmd in (0x06, 0x07))
        elif cmd == 0x12:
            for i, b in enumerate(self.cal):
                self.regs[0x22 + i] = b
        elif cmd not in (0x02, 0x09, 0x0A, 0x0B, 0x0D, 0x0E, 0x0F):
            self.regs[0x20] = 0x80
            return
        self.regs[0x20] = (resp + 1) & 0x0F

    def put16(self, reg, v):
        self.regs[reg] = v & 0xFF
        self.regs[reg + 1] = (v >> 8) & 0xFF

    def measure(self, ps, als):
        ch = self.params[0x01]
        status = 0
        if als and ch & 0xF0:
            if ch & 0x10:
                self.put16(0x22, self.samples['vis'])
            if ch & 0x20:
                self.put16(0x24, self.samples['ir'])
            if ch & 0x80:
                self.put16(0x2C, self.samples['uv'])
            elif ch & 0x40:
                self.put16(0x2C, self.samples['temp'] if self.params[0x0F] == 0x65 else self.samples['vdd'])
            status |= 0x01
        if ps:
            for i, name in enumerate(('ps1', 'ps2', 'ps3')):
                if ch & (1 << i):
                    self.put16(0x26 + 2 * i, self.samples[name])
                    status |= 0x04 << i
        self.regs[0x21] |= status & self.regs[0x04]

    def write(self, data):
        data = bytes(data)
        self.writes.append(data)
        reg = data[0]
        for i, v in enumerate(data[1:]):
            r = reg + i
            if r == 0x18:
                self.command(v)
            elif r == 0x21:
                self.regs[0x21] &= ~v & 0xFF
            else:
                self.regs[r] = v

    def write_read(self, data, n):
        self.writes.append(bytes(data))
        reg = bytes(data)[0]
        return bytes(self.regs[reg + i] for i in range(n))


def raises(exc, fn, *args, **kw):
    try:
        fn(*args, **kw)
        return False
    except exc:
        return True


# --- init ---
c = Si1145Sim()
s = Si1145Full(c)
check_true('constructor_no_bus', c.writes == [])
s.init()
check_true('init_hw_key', c.regs[0x07] == 0x17)
check_true('init_ucoef', c.regs[0x13:0x17] == [0x7B, 0x6B, 0x01, 0x00])
check_true('init_chlist', c.params[0x01] == 0xB1)
check_true('init_psled12', c.params[0x02] == 0x01)
check_true('init_ps_led21', c.regs[0x0F] == 0x03)
check_true('init_irq_enable', c.regs[0x04] == 0x05)
check_true('init_param_set_sequence', bytes((0x17, 0xB1)) in c.writes and 0xA1 in c.commands)

bad = Si1145Sim(part_id=0x32)
check_true('init_bad_part_id', raises(OSError, Si1145Minimal(bad).init))

# --- read ---
m = s.read()
check_true('read_proximity', m['proximity'] == 1500)
check_true('read_uv_index', abs(m['uv_index'] - 3.45) < 1e-9)
check_true('read_visible', m['visible'] == 260)
check_true('read_infrared', m['infrared'] == 300)
check_true('read_psals_force', 0x07 in c.commands)
check_true('read_irq_cleared', c.regs[0x21] == 0)

# --- single reads ---
c.samples['ps1'] = 42
check_true('read_proximity_ps1', s.read_proximity() == 42 and c.commands[-1] == 0x05)
check_true('read_proximity_ps2_unsupported', raises(ValueError, s.read_proximity, 2))
c.samples['uv'] = 1000
check_true('read_uv_index_single', s.read_uv_index() == 10.0 and c.commands[-1] == 0x06)
check_true('read_visible_single', s.read_visible() == 260)
check_true('read_infrared_single', s.read_infrared() == 300)
raw = s.read_raw()
check_true('read_raw', raw == {'visible': 260, 'infrared': 300, 'ps1': 42, 'ps2': 0, 'ps3': 0, 'aux': 1000})

check_true('read_temperature_raw', s.read_temperature_raw() == 0x1111 and c.params[0x0F] == 0x65)
check_true('read_aux_restores_chlist', c.params[0x01] == 0xB1)
check_true('read_vdd_raw', s.read_vdd_raw() == 0x2222 and c.params[0x0F] == 0x75)

# --- channels ---
s.set_channels(False, True, False, True)
check_true('set_channels', c.params[0x01] == 0x11)
check_true('read_uv_disabled', raises(ValueError, s.read_uv_index))
check_true('read_infrared_disabled', raises(ValueError, s.read_infrared))
check_true('set_channels_uv_aux', raises(ValueError, s.set_channels, True, False, False, False, aux=True))
check_true('set_channels_ps2_si1145', raises(ValueError, s.set_channels, False, False, False, True, ps2=True))
s.set_channels(False, False, False, False, aux=True)
check_true('set_channels_aux', c.params[0x01] == 0x40)
s.set_channels(True, True, True, True)
check_true('set_channels_default', c.params[0x01] == 0xB1)

# --- LEDs, photodiode ---
s.set_led_current(1, 15)
check_true('set_led_current', c.regs[0x0F] == 0x0F)
check_true('set_led_current_led2_si1145', raises(ValueError, s.set_led_current, 2, 1))
check_true('set_led_current_level', raises(ValueError, s.set_led_current, 1, 16))
s.set_ps_led(1, 0)
check_true('set_ps_led_off', c.params[0x02] == 0x00)
s.set_ps_led(1, 1)
check_true('set_ps_led', c.params[0x02] == 0x01)
check_true('set_ps_led_led2_si1145', raises(ValueError, s.set_ps_led, 1, 2))
s.set_ps_photodiode(1, False)
check_true('set_ps_photodiode_small', c.params[0x07] == 0x00)
s.set_ps_photodiode(1, True)
check_true('set_ps_photodiode_large', c.params[0x07] == 0x03)

c47 = Si1145Sim(part_id=0x47)
s47 = Si1145Full(c47)
s47.init()
s47.set_led_current(2, 5)
s47.set_led_current(3, 9)
check_true('si1147_led_current', c47.regs[0x0F] == 0x53 and c47.regs[0x10] == 0x09)
s47.set_ps_led(2, 2)
s47.set_ps_led(3, 4)
check_true('si1147_ps_led', c47.params[0x02] == 0x21 and c47.params[0x03] == 0x04)
s47.set_channels(False, False, False, True, ps2=True, ps3=True)
check_true('si1147_read_ps3', s47.read_proximity(3) == 1700)

# --- gain, range, alignment, UCOEF ---
s.set_gain(visible=3, infrared=2, proximity=1)
check_true('set_gain_vis', c.params[0x11] == 3 and c.params[0x10] == 0x40)
check_true('set_gain_ir', c.params[0x1E] == 2 and c.params[0x1D] == 0x50)
check_true('set_gain_ps', c.params[0x0B] == 1 and c.params[0x0A] == 0x60)
check_true('set_gain_range', raises(ValueError, s.set_gain, 8))
s.set_gain()
check_true('set_gain_default', c.params[0x10] == 0x70 and c.params[0x11] == 0)
s.set_high_range(True, True, True)
check_true('set_high_range', c.params[0x12] == 0x20 and c.params[0x1F] == 0x20 and c.params[0x0C] == 0x24)
s.set_high_range(False, False, False)
check_true('set_high_range_off', c.params[0x0C] == 0x04)
s.set_alignment(visible=True, ps1=True)
check_true('set_alignment', c.params[0x06] == 0x10 and c.params[0x05] == 0x10)
s.set_uv_coefficients((1, 2, 3, 4))
check_true('set_uv_coefficients', c.regs[0x13:0x17] == [1, 2, 3, 4])
check_true('set_uv_coefficients_len', raises(ValueError, s.set_uv_coefficients, (1, 2, 3)))

# --- calibration ---
check_true('read_calibration', s.read_calibration() == list(range(0x10, 0x1C)) and c.commands[-1] == 0x12)

# --- autonomous ---
s.start_autonomous(0.1)
check_true('start_autonomous_rate', c.regs[0x08] == (3200 & 0xFF) and c.regs[0x09] == 3200 >> 8)
check_true('start_autonomous_cmd', c.commands[-1] == 0x0F)
s.start_autonomous(0.01, proximity=False)
check_true('start_autonomous_als', c.commands[-1] == 0x0E and c.regs[0x08] == 64)
s.start_autonomous(0.01, als=False)
check_true('start_autonomous_ps', c.commands[-1] == 0x0D)
check_true('start_autonomous_range', raises(ValueError, s.start_autonomous, 3.0))
check_true('start_autonomous_none', raises(ValueError, s.start_autonomous, 0.1, False, False))
s.pause()
check_true('pause', c.commands[-1] == 0x0B)

cs = Si1145Sim(seq_id=0x01)
ss = Si1145Full(cs)
ss.init()
ss.start_autonomous(0.1)
check_true('start_autonomous_swapped', cs.regs[0x0A] == (3200 & 0xFF) and cs.regs[0x08] == 3200 >> 8)

s.force()
check_true('force_psals', c.commands[-1] == 0x07)
s.force(als=False)
check_true('force_ps', c.commands[-1] == 0x05)
s.force(proximity=False)
check_true('force_als', c.commands[-1] == 0x06)

# --- response, errors ---
counter, err = s.read_response()
check_true('read_response', err == 0 and counter == c.regs[0x20])
c.fail_param = 0x0B
check_true('param_error', raises(OSError, s.set_gain, 0, 0, 1))
check_true('param_error_nop', c.commands[-1] == 0x00 and c.regs[0x20] == 0)
c.fail_param = None
c.regs[0x20] = 0x8C
counter, err = s.read_response()
check_true('read_response_error', err == 0x8C and counter == 0)
s.set_gain()
check_true('pending_error_cleared', c.regs[0x20] & 0x80 == 0 and c.params[0x0B] == 0)

# --- interrupt ---
s.enable_interrupt(Si1145Full.SOURCE_CMD)
check_true('enable_interrupt', c.regs[0x03] == 0x01 and c.regs[0x04] == 0x25)
s.disable_interrupt(Si1145Full.SOURCE_CMD)
check_true('disable_interrupt', c.regs[0x04] == 0x05)
c.regs[0x21] = 0x05
check_true('poll_interrupt', s.poll_interrupt() == 0x05)
check_true('poll_interrupt_cleared', c.regs[0x21] == 0 and s.poll_interrupt() == 0)


class FakePin:
    FALLING = 2

    def __init__(self):
        self.handler = None
        self.edge = None

    def on_edge(self, handler, edge):
        self.handler = handler
        self.edge = edge

    def off_edge(self, handler):
        self.handler = None


events = []
pin = FakePin()
s.on_interrupt(events.append, int_pin=pin)
check_true('on_interrupt_falling', pin.edge == FakePin.FALLING)
c.regs[0x21] = 0x04
pin.handler(pin)
check_true('on_interrupt', events == [0x04])
pin.handler(pin)
check_true('on_interrupt_no_duplicate', events == [0x04])
s.off_interrupt()
check_true('off_interrupt', pin.handler is None)

s.disable_interrupt(0x3D)
check_true('read_without_irq', s.read()['proximity'] == 42)

# --- identity, address, reset ---
check_true('read_id', s.read_id() == (0x45, 0x00, 0x08))
s.set_i2c_address(0x30)
check_true('set_i2c_address', c.params[0x00] == 0x30 and c.commands[-1] == 0x02)
check_true('set_i2c_address_invalid', raises(ValueError, s.set_i2c_address, 0x80))

c.regs[0x0F] = 0x0F
s.reset()
check_true('reset_cmd', 0x01 in c.commands)
check_true('reset_defaults', c.regs[0x07] == 0x17 and c.params[0x01] == 0xB1 and c.regs[0x0F] == 0x03)
check_true('reset_read', s.read()['proximity'] == 42)

# --- timeouts ---
stuck = Si1145Sim()
s2 = Si1145Minimal(stuck)
s2.init()
stuck.ignore_commands = True
check_true('measure_timeout', raises(OSError, s2.read))
stuck.ignore_commands = False
s3 = Si1145Full(stuck)
s3.init()
stuck.ignore_commands = True
check_true('command_timeout', raises(OSError, s3.pause))

print('===DONE: %d passed, %d failed===' % (passed, failed))
sys.exit(0 if failed == 0 else 1)
