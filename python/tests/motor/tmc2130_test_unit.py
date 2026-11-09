"""Unit test for the TMC2130 — runs without hardware using a local chip simulator.

The simulator implements the pipelined 40-bit SPI datagram protocol: every
reply carries the register requested by (or the data written in) the
previous datagram.
"""

from periph.chips.motor.tmc2130 import TMC2130Minimal, TMC2130Full

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


class ChipSim:
    """Scripted TMC2130: write(5 bytes) / write_read(addr, 4)."""

    def __init__(self):
        self.regs = {0x04: 0x11000040, 0x01: 0x01}
        self.frames = []
        self.writes = []
        self.pending = 0
        self.mirror_broken = False
        self.gstat_reads = 0

    def _datagram(self, frame):
        self.frames.append(bytes(frame))
        reply = self.pending
        reg = frame[0] & 0x7F
        if frame[0] & 0x80:
            value = int.from_bytes(frame[1:5], 'big')
            self.regs[reg] = value
            self.writes.append((reg, value))
            self.pending = 0 if self.mirror_broken else value
        else:
            if reg == 0x01:
                self.gstat_reads += 1
            self.pending = self.regs.get(reg, 0)
            if reg == 0x01:
                self.regs[0x01] = 0
        return reply

    def write(self, data):
        data = bytes(data)
        assert len(data) == 5
        self._datagram(data)

    def write_read(self, data, n):
        data = bytes(data)
        assert n == 4
        frame = data + bytes(5 - len(data))
        return self._datagram(frame).to_bytes(4, 'big')

    def last_write(self, reg):
        values = [v for r, v in self.writes if r == reg]
        return values[-1] if values else None


class Pin:
    def __init__(self):
        self.levels = []

    def set(self, high):
        self.levels.append(bool(high))


class InPin:
    def __init__(self, level):
        self.level = level

    def is_high(self):
        return self.level


def make(cls=TMC2130Minimal, **kw):
    sim = ChipSim()
    step, dirp = Pin(), Pin()
    return sim, step, dirp, cls(sim, step, dirp, **kw)


# ---- Minimal ----
sim, step, dirp, m = make()
check_true('chopconf_write_frame', bytes([0xEC, 0x14, 0x03, 0x00, 0xC3]) in sim.frames)
check_true('ioin_read_frames', bytes([0x04, 0, 0, 0, 0]) in sim.frames and bytes([0x00, 0, 0, 0, 0]) in sim.frames)
check_true('init_chopconf', sim.last_write(0x6C) == 0x140100C3 | (1 << 17))
check_true('init_tpowerdown', sim.last_write(0x11) == 10)
check_true('init_gconf', sim.last_write(0x00) == 0x4)
check_true('init_tpwmthrs', sim.last_write(0x13) == 500)
check_true('init_pwmconf', sim.last_write(0x70) == 0x000401C8)
ih = sim.last_write(0x10)
check_true('init_current_0_5a', ((ih >> 8) & 0x1F) == 15 and (ih & 0x1F) == 5)
check_true('init_ihold_delay', ((ih >> 16) & 0xF) == 6)
check_true('init_clears_gstat', sim.gstat_reads == 1)
# GSTAT (clear on read) is read exactly once, by init -- never as a second datagram.

# set_current: 1.0 A -> vsense 0 CS=17 (>=16) ; 0.5 A -> vsense 1 CS=15
m.set_current(1.0)
check_true('set_current_1a', ((sim.last_write(0x10) >> 8) & 0x1F) == 17
           and not sim.last_write(0x6C) & (1 << 17))
m.set_current(0.5)
check_true('set_current_auto_vsense', ((sim.last_write(0x10) >> 8) & 0x1F) == 15
           and bool(sim.last_write(0x6C) & (1 << 17)))
try:
    m.set_current(1.5)
    check_true('set_current_range', False)
except ValueError:
    check_true('set_current_range', True)

# move: 2 full steps at 16 microsteps -> 32 pulses, DIR high / low
m.move(2, 1000000.0)
check_true('move_pulses', step.levels.count(True) == 32 and dirp.levels[-1] is True)
m.move(-1, 1000000.0)
check_true('move_reverse', dirp.levels[-1] is False and step.levels.count(True) == 48)
try:
    m.move(1, 0)
    check_true('move_speed_range', False)
except ValueError:
    check_true('move_speed_range', True)

# disable/enable without ENN toggles TOFF
m.disable()
check_true('disable_toff0', sim.last_write(0x6C) & 0xF == 0)
m.enable()
check_true('enable_toff3', sim.last_write(0x6C) & 0xF == 3)

sim.regs[0x6F] = (1 << 31) | (1 << 26) | (1 << 28) | (1 << 30)
check_true('standstill', m.is_standstill())
check_true('fault_flags', m.read_fault() == (TMC2130Minimal.FAULT_OVERTEMP_WARNING
                                              | TMC2130Minimal.FAULT_SHORT_GND
                                              | TMC2130Minimal.FAULT_OPEN_LOAD))

# ENN pin
en = Pin()
sim2 = ChipSim()
m2 = TMC2130Minimal(sim2, Pin(), Pin(), en_pin=en)
check_true('en_pin_high_at_init', en.levels == [True])
m2.enable()
check_true('en_pin_low_on_enable', en.levels[-1] is False)

# wrong identity / broken mirror
for label, setup in (('bad_version', lambda s: s.regs.__setitem__(0x04, 0)),
                     ('bad_bit6', lambda s: s.regs.__setitem__(0x04, 0x11000000)),
                     ('broken_mirror', lambda s: setattr(s, 'mirror_broken', True))):
    bad = ChipSim()
    setup(bad)
    try:
        TMC2130Minimal(bad, Pin(), Pin())
        check_true(label + '_raises', False)
    except OSError:
        check_true(label + '_raises', True)

# ---- Full ----
sim, step, dirp, f = make(TMC2130Full, diag0_pin=InPin(True))
f.set_microsteps(256)
check_true('microsteps_256', (sim.last_write(0x6C) >> 24) & 0xF == 0)
f.set_microsteps(1)
check_true('microsteps_1', (sim.last_write(0x6C) >> 24) & 0xF == 8)
try:
    f.set_microsteps(3)
    check_true('microsteps_invalid', False)
except ValueError:
    check_true('microsteps_invalid', True)
f.set_microsteps(32)
f.move(1, 1000000.0)
check_true('move_uses_microsteps', step.levels.count(True) == 32)
f.set_interpolation(False)
check_true('intpol_off', (sim.last_write(0x6C) >> 28) & 1 == 0)
f.set_chopper_mode(True)
check_true('spreadcycle', sim.last_write(0x00) & 0x4 == 0)
f.set_chopper_mode(False)
check_true('stealthchop', sim.last_write(0x00) & 0x4 == 0x4)
f.set_direction_inverted(True)
check_true('shaft', sim.last_write(0x00) & 0x10 == 0x10)
f.configure_diag(diag0_stall=True, diag1_index=True, diag0_pushpull=True)
g = sim.last_write(0x00)
check_true('diag_bits', (g >> 7) & 1 and (g >> 9) & 1 and (g >> 12) & 1 and not (g >> 5) & 1
           and g & 0x14 == 0x14)
f.set_double_edge(True)
check_true('dedge', (sim.last_write(0x6C) >> 29) & 1 == 1)
f.set_short_protection(False)
check_true('diss2g', (sim.last_write(0x6C) >> 30) & 1 == 1)
f.set_vsense(False)
check_true('vsense_manual', not (sim.last_write(0x6C) >> 17) & 1)
f.set_current(0.5)
check_true('manual_vsense_kept', not (sim.last_write(0x6C) >> 17) & 1
           and ((sim.last_write(0x10) >> 8) & 0x1F) == 8)
f.set_hold_current(0.2)
check_true('hold_current', sim.last_write(0x10) & 0x1F == 3)
f.set_current_delay(2, 100)
check_true('current_delay', ((sim.last_write(0x10) >> 16) & 0xF) == 2
           and sim.last_write(0x11) == 100)
f.set_stealthchop_threshold(300)
check_true('tpwmthrs', sim.last_write(0x13) == 300)
f.set_high_velocity_threshold(77)
check_true('thigh', sim.last_write(0x15) == 77)
f.configure_chopper(4, 1, 3, 2)
c = sim.last_write(0x6C)
check_true('configure_chopper', (c & 0xF) == 4 and ((c >> 15) & 3) == 1
           and ((c >> 4) & 7) == 3 and ((c >> 7) & 0xF) == 2 and not (c >> 14) & 1)
f.configure_constant_off_time(5, 2, 11, -3, True, True)
c = sim.last_write(0x6C)
check_true('configure_constant_off_time', (c & 0xF) == 5 and ((c >> 15) & 3) == 2
           and (c >> 14) & 1 and ((c >> 4) & 7) == 3 and (c >> 11) & 1
           and ((c >> 7) & 0xF) == 0 and (c >> 12) & 1 and (c >> 13) & 1)
f.configure_high_velocity(True, True)
check_true('high_velocity', (sim.last_write(0x6C) >> 18) & 3 == 3)
f.set_chopper_sync(5)
check_true('chopper_sync', (sim.last_write(0x6C) >> 20) & 0xF == 5)
f.configure_stealthchop(True, False, 40, 5, 1)
p = sim.last_write(0x70)
check_true('configure_stealthchop', (p & 0xFF) == 40 and ((p >> 8) & 0xFF) == 5
           and ((p >> 16) & 3) == 1 and (p >> 18) & 1 and not (p >> 19) & 1)
f.set_freewheel(1)
p = sim.last_write(0x70)
check_true('freewheel', (p >> 20) & 3 == 1 and (p & 0xFF) == 40)
f.set_small_hysteresis(True)
check_true('small_hysteresis', (sim.last_write(0x00) >> 14) & 1 == 1)
f.set_emergency_stop(True)
check_true('stop_enable', (sim.last_write(0x00) >> 15) & 1 == 1)
f.set_analog_scaling(True)
check_true('analog_scaling', sim.last_write(0x00) & 1 == 1)

# StallGuard2 / CoolStep
f.set_stall_threshold(-5)
check_true('sgt_negative', (sim.last_write(0x6D) >> 16) & 0x7F == (-5 & 0x7F))
f.set_stallguard_filter(True)
f.configure_coolstep(5, 2, 1, 1, 0)
cc = sim.last_write(0x6D)
check_true('coolconf_keeps_sgt', (cc & 0xF) == 5 and ((cc >> 5) & 3) == 1
           and ((cc >> 8) & 0xF) == 2 and ((cc >> 13) & 3) == 1
           and (cc >> 24) & 1 and (cc >> 16) & 0x7F == (-5 & 0x7F))
for bad_call in (lambda: f.set_stall_threshold(64), lambda: f.set_stall_threshold(-65),
                 lambda: f.configure_coolstep(16, 0, 0, 0, 0), lambda: f.set_coolstep_threshold(1 << 20)):
    try:
        bad_call()
        check_true('range_error', False)
    except ValueError:
        check_true('range_error', True)
f.set_coolstep_threshold(500)
check_true('tcoolthrs', sim.last_write(0x14) == 500)
sim.regs[0x6F] = (1 << 24) | (17 << 16) | 0x2A
check_true('read_stallguard', f.read_stallguard() == 0x2A)
check_true('is_stalled', f.is_stalled())
check_true('read_diag0', f.read_diag(0) is True)
for bad_call, exc in ((lambda: f.read_diag(1), RuntimeError), (lambda: f.read_diag(2), ValueError)):
    try:
        bad_call()
        check_true('read_diag_error', False)
    except exc:
        check_true('read_diag_error', True)

# direct mode / DcStep / table
try:
    f.set_coil_currents(1, 1)
    check_true('coil_requires_direct', False)
except RuntimeError:
    check_true('coil_requires_direct', True)
f.set_direct_mode(True)
f.set_coil_currents(100, -100)
check_true('xdirect', sim.last_write(0x2D) == (100 | ((-100 & 0x1FF) << 16)))
f.set_dcstep(1000, 200, 3)
check_true('dcstep', sim.last_write(0x33) == 1000 and sim.last_write(0x6E) == 200 | (3 << 16))
f.write_microstep_table(list(range(1, 9)), 0x12345678, 0x00F70000)
check_true('mslut', sim.last_write(0x60) == 1 and sim.last_write(0x67) == 8
           and sim.last_write(0x68) == 0x12345678 and sim.last_write(0x69) == 0x00F70000)

# read-backs
sim.regs[0x6A] = 513
sim.regs[0x6B] = (0x1FF << 16) | 0x100
sim.regs[0x12] = 1234
sim.regs[0x71] = 77
sim.regs[0x73] = 0x123456
sim.regs[0x6F] = (1 << 31) | (1 << 15) | (17 << 16) | (1 << 25) | (1 << 24) | 300
sim.regs[0x04] = 0x11000000 | 0x40 | 0x1 | 0x10
sim.regs[0x01] = 0x03
check_true('microstep_position', f.microstep_position() == 513)
check_true('coil_currents', f.read_coil_currents() == (-1, -256))
check_true('tstep', f.read_tstep() == 1234)
check_true('pwm_scale', f.read_pwm_scale() == 77)
check_true('lost_steps', f.read_lost_steps() == 0x23456)
check_true('current_scale', f.read_current_scale() == 17)
st = f.read_status()
check_true('status', st['stst'] and st['fsactive'] and st['ot'] and st['stallguard']
           and st['cs_actual'] == 17 and st['sg_result'] == 300 and not st['otpw'])
check_true('global_status', f.read_global_status() == {'reset': True, 'drv_err': True, 'uv_cp': False})
check_true('global_status_clears', sim.regs[0x01] == 0)
io = f.read_ioin()
check_true('ioin', io['version'] == 0x11 and io['step'] and io['enn'] and not io['dir'])
check_true('read_register', f.read_register(0x6A) == 513)
try:
    f.read_register(0x10)
    check_true('read_register_writeonly', False)
except ValueError:
    check_true('read_register_writeonly', True)
f.write_register(0x13, 5, verify=True)
check_true('write_register', sim.last_write(0x13) == 5)
try:
    f.write_register(0x72, 1)
    check_true('write_register_encm', False)
except ValueError:
    check_true('write_register_encm', True)

# internal Rsense
f.set_internal_rsense(True)
try:
    f.set_current(0.5)
    check_true('internal_rsense_current', False)
except ValueError:
    check_true('internal_rsense_current', True)
f.set_internal_rsense(False)

# GPIO stepping
f.step(3, 0)
check_true('step_pulses', step.levels.count(True) == 32 + 3 and dirp.levels[-1] is True)
f.step(-2, 0)
check_true('step_reverse', dirp.levels[-1] is False)

print('===DONE: %d passed, %d failed===' % (passed, failed))

if failed:
    raise SystemExit(1)
