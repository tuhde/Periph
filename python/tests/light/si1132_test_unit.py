import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.light.si1132 import Si1132Full, Si1132Minimal

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
    """Models the Si1132 sequencer: RESPONSE counter, parameter RAM, forced measurement."""

    def __init__(self, seq_id=0x08, part_id=0x32):
        I2CConnectionMock.__init__(self)
        self.registers[0x00] = part_id
        self.registers[0x02] = seq_id
        self.params = {}
        self.cmds = []
        self.vis = 1234
        self.ir = 567
        self.aux = 250     # UV index 2.50
        self.aux_by_mux = {0x65: 0x4000, 0x75: 0x2000}
        self.cmd_error = None

    def _count(self):
        self.registers[0x20] = (self.registers.get(0x20, 0) + 1) & 0x0F

    def write(self, data):
        data = bytes(data)
        I2CConnectionMock.write(self, data)
        if len(data) < 2:
            return
        reg, val = data[0], data[1]
        if reg == 0x21:   # write 1 to clear
            self.registers[0x21] = self.registers.get(0x21, 0) & ~val & 0xFF
        elif reg == 0x18:
            self.cmds.append(val)
            self._command(val)

    def _command(self, cmd):
        if self.cmd_error is not None and cmd != 0x00:
            self.registers[0x20] = self.cmd_error
            return
        if cmd & 0xE0 == 0xA0:
            self.params[cmd & 0x1F] = self.registers.get(0x17, 0)
            self.registers[0x2E] = self.params[cmd & 0x1F]
            self._count()
        elif cmd & 0xE0 == 0x80:
            self.registers[0x2E] = self.params.get(cmd & 0x1F, 0)
            self._count()
        elif cmd == 0x00:
            self.registers[0x20] = 0
        elif cmd == 0x06:
            self._measure()
            self._count()
        elif cmd == 0x12:
            for i in range(12):
                self.registers[0x22 + i] = 0x10 + i
            self._count()
        elif cmd == 0x01:
            pass
        else:
            self._count()

    def _measure(self):
        aux = self.aux
        if self.params.get(0x01, 0) == 0x40:
            aux = self.aux_by_mux.get(self.params.get(0x0F, 0), 0)
        for off, v in ((0x22, self.vis), (0x24, self.ir), (0x2C, aux)):
            self.registers[off] = v & 0xFF
            self.registers[off + 1] = v >> 8
        self.registers[0x21] = self.registers.get(0x21, 0) | 0x01


# --- Minimal ---
c = ChipMock()
s = Si1132Minimal(c)
check_true('init_hw_key', bytes([0x07, 0x17]) in c.writes)
check_true('init_ucoef', bytes([0x13, 0x7B, 0x6B, 0x01, 0x00]) in c.writes)
check_true('init_chlist', c.params[0x01] == 0xB0)
check_true('init_irq_enable', c.registers[0x04] == 0x01)
m = s.read()
check_true('read_uv_index', near(m['uv_index'], 2.5))
check_true('read_visible', m['visible'] == 1234)
check_true('read_infrared', m['infrared'] == 567)
check_true('read_forces_measurement', 0x06 in c.cmds)
check_true('read_clears_status', c.registers[0x21] == 0)

try:
    Si1132Minimal(ChipMock(part_id=0x45))
    check_true('wrong_part_id', False)
except OSError:
    check_true('wrong_part_id', True)

# timeout: chip that never completes a measurement
class Stuck(ChipMock):
    def _measure(self):
        pass

st = Stuck()
ss = Si1132Minimal(st)
try:
    ss.read()
    check_true('measurement_timeout', False)
except OSError:
    check_true('measurement_timeout', True)

# command error
ce = ChipMock()
se = Si1132Full(ce)
ce.cmd_error = 0x80
try:
    se.set_gain(1, 1)
    check_true('response_error', False)
except OSError:
    check_true('response_error', True)
check_true('response_error_nop', ce.cmds[-1] == 0x00)

# --- Full ---
c = ChipMock()
f = Si1132Full(c)
check_true('full_is_minimal', isinstance(f, Si1132Minimal))
check_true('read_uv_index_full', near(f.read_uv_index(), 2.5))
check_true('read_visible_full', f.read_visible() == 1234)
check_true('read_infrared_full', f.read_infrared() == 567)
r = f.read_raw()
check_true('read_raw', r == {'visible': 1234, 'infrared': 567, 'aux': 250})

check_true('read_temperature_raw', f.read_temperature_raw() == 0x4000)
check_true('temperature_mux', c.params[0x0F] == 0x65)
check_true('temperature_restores_chlist', c.params[0x01] == 0xB0)
check_true('read_vdd_raw', f.read_vdd_raw() == 0x2000)
check_true('vdd_mux', c.params[0x0F] == 0x75)

f.set_channels(True, False, True)
check_true('set_channels', c.params[0x01] == 0xA0)
f.set_channels(False, True, False, aux=True)
check_true('set_channels_aux', c.params[0x01] == 0x50)
for bad in ((False, False, False), (True, True, True, True)):
    try:
        f.set_channels(*bad)
        check_true('set_channels_invalid', False)
    except ValueError:
        check_true('set_channels_invalid', True)
f.set_channels(True, True, True)

f.set_gain(3, 5)
check_true('gain_vis', c.params[0x11] == 3 and c.params[0x10] == 0x40)
check_true('gain_ir', c.params[0x1E] == 5 and c.params[0x1D] == 0x20)
f.set_gain()
check_true('gain_default', c.params[0x11] == 0 and c.params[0x10] == 0x70)
try:
    f.set_gain(8, 0)
    check_true('gain_invalid', False)
except ValueError:
    check_true('gain_invalid', True)

f.set_high_range(True, False)
check_true('high_range', c.params[0x12] == 0x20 and c.params[0x1F] == 0x00)
f.set_alignment(True, False)
check_true('alignment', c.params[0x06] == 0x10)
f.set_alignment(True, True)
check_true('alignment_both', c.params[0x06] == 0x30)

f.set_uv_coefficients((1, 2, 3, 4))
check_true('uv_coefficients', bytes([0x13, 1, 2, 3, 4]) in c.writes)
try:
    f.set_uv_coefficients((1, 2))
    check_true('uv_coefficients_invalid', False)
except ValueError:
    check_true('uv_coefficients_invalid', True)

cal = f.read_calibration()
check_true('read_calibration', cal == [0x10 + i for i in range(12)])
check_true('read_calibration_command', 0x12 in c.cmds)

# autonomous mode: 0.5 s = 16000 counts = 0x3E80
f.start_autonomous(0.5)
check_true('autonomous_rate', c.registers[0x08] == 0x80 and c.registers[0x09] == 0x3E)
check_true('autonomous_command', c.cmds[-1] == 0x0E)
f.start_autonomous(0)
check_true('autonomous_stop', c.cmds[-1] == 0x0A)
for bad in (-1, 3.0):
    try:
        f.start_autonomous(bad)
        check_true('autonomous_invalid', False)
    except ValueError:
        check_true('autonomous_invalid', True)

# SEQ_ID 1 erratum: bytes swapped
c1 = ChipMock(seq_id=0x01)
f1 = Si1132Full(c1)
f1.start_autonomous(0.5)
check_true('autonomous_erratum', c1.registers[0x0A] == 0x80 and c1.registers[0x08] == 0x3E)

f.pause()
check_true('pause', c.cmds[-1] == 0x0A)
f.force()
check_true('force', c.cmds[-1] == 0x06)
check_true('measurement_ready', f.measurement_ready())
c.registers[0x21] = 0
check_true('measurement_not_ready', not f.measurement_ready())

c.registers[0x20] = 0x05
check_true('read_response_counter', f.read_response() == (5, 0))
c.registers[0x20] = 0x8C
check_true('read_response_error', f.read_response() == (0, 0x8C))

f.set_i2c_address(0x61)
check_true('set_i2c_address', c.params[0x00] == 0x61 and c.cmds[-1] == 0x02)

f.reset()
check_true('reset_command', 0x01 in c.cmds)
check_true('reset_rewrites_hw_key', c.writes[-1] == bytes([0x07, 0x17]))

# interrupt
f.enable_interrupt()
check_true('enable_interrupt', c.registers[0x03] == 0x01 and c.registers[0x04] == 0x01)
f.disable_interrupt()
check_true('disable_interrupt', c.registers[0x03] == 0x00)
c.registers[0x21] = 0x01
check_true('poll_interrupt', f.poll_interrupt() == 0x01)
check_true('poll_interrupt_clears', c.registers[0x21] == 0)
check_true('poll_interrupt_none', f.poll_interrupt() == 0)

class Pin:
    def __init__(self):
        self.handlers = []
    def on_edge(self, handler, edge):
        self.handlers.append((handler, edge))
    def off_edge(self, handler):
        self.handlers = [h for h in self.handlers if h[0] is not handler]

pin = Pin()
got = []
f.on_interrupt(got.append, int_pin=pin)
check_true('on_interrupt_enables', c.registers[0x03] == 0x01)
check_true('on_interrupt_subscribes', len(pin.handlers) == 1)
c.registers[0x21] = 0x01
pin.handlers[0][0]()
check_true('on_interrupt_callback', got == [0x01])
f.off_interrupt()
check_true('off_interrupt_unsubscribes', len(pin.handlers) == 0)
check_true('off_interrupt_releases', c.registers[0x03] == 0x00)

print('===DONE: %d passed, %d failed===' % (passed, failed))
sys.exit(1 if failed else 0)
