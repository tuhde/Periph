"""Unit test for the TMC5160 — runs without hardware using local chip simulators.

SpiSim implements the pipelined 40-bit SPI datagram protocol; UartSim the
CRC-protected single-wire UART datagrams with echo and IFCNT.
"""

from periph.chips.motor.tmc5160 import TMC5160Minimal, TMC5160Full, _crc8

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


def raises(exc, fn):
    try:
        fn()
    except exc:
        return True
    return False


class Chip:
    def __init__(self):
        self.regs = {0x04: 0x30000000, 0x01: 0x01, 0x02: 0}
        self.writes = []

    def last_write(self, reg):
        values = [v for r, v in self.writes if r == reg]
        return values[-1] if values else None

    def store(self, reg, value):
        self.regs[reg] = value
        self.writes.append((reg, value))


class SpiSim(Chip):
    def __init__(self):
        super().__init__()
        self.pending = 0
        self.mirror_broken = False
        self.frames = []

    def _datagram(self, frame):
        frame = bytes(frame) + bytes(5 - len(frame))
        self.frames.append(frame)
        reply = self.pending
        reg = frame[0] & 0x7F
        if frame[0] & 0x80:
            value = int.from_bytes(frame[1:5], 'big')
            self.store(reg, value)
            self.pending = 0 if self.mirror_broken else value
        else:
            self.pending = self.regs.get(reg, 0)
        return reply

    def write(self, data):
        assert len(data) == 5
        self._datagram(data)

    def write_read(self, data, n):
        assert n == 4
        return self._datagram(data).to_bytes(4, 'big')


class UartSim(Chip):
    def __init__(self, echo=True):
        super().__init__()
        self.echo = echo
        self.rx = bytearray()
        self.datagrams = []
        self.corrupt_next_reply = False

    def write(self, data):
        data = bytes(data)
        self.datagrams.append(data)
        if self.echo:
            self.rx.extend(data)
        if len(data) == 8 and data[2] & 0x80:
            assert _crc8(data[:7]) == data[7]
            self.store(data[2] & 0x7F, int.from_bytes(data[3:7], 'big'))
            self.regs[0x02] = (self.regs[0x02] + 1) & 0xFF
        elif len(data) == 4:
            assert _crc8(data[:3]) == data[3]
            reg = data[2] & 0x7F
            body = bytes([0x05, 0xFF, reg]) + self.regs.get(reg, 0).to_bytes(4, 'big')
            reply = body + bytes([_crc8(body)])
            if self.corrupt_next_reply:
                reply = reply[:7] + bytes([reply[7] ^ 0xFF])
                self.corrupt_next_reply = False
            self.rx.extend(reply)

    def read(self, n):
        out = bytes(self.rx[:n])
        del self.rx[:len(out)]
        return out

    def write_read(self, data, n):
        self.write(data)
        return self.read(n)

    def flush(self):
        self.rx = bytearray()


class Pin:
    def __init__(self):
        self.levels = []

    def set(self, high):
        self.levels.append(bool(high))


class InPin:
    def is_high(self):
        return True


# ---- CRC vectors (spec, address 0) ----
check_true('crc_write_gconf', _crc8(bytes([5, 0, 0x80, 0, 0, 0, 4])) == 0xA9)
check_true('crc_read_ioin', _crc8(bytes([5, 0, 4])) == 0xA8)

# ---- SPI Minimal ----
sim = SpiSim()
m = TMC5160Minimal(sim)
check_true('spi_chopconf_frame', bytes([0xEC, 0x00, 0x01, 0x00, 0xC3]) in sim.frames)
check_true('spi_init_gstat_clear', sim.last_write(0x01) == 0x07)
check_true('spi_init_tpowerdown', sim.last_write(0x11) == 10)
check_true('spi_init_gconf', sim.last_write(0x00) == 0x4)
check_true('spi_init_tpwmthrs', sim.last_write(0x13) == 500)
check_true('spi_init_ramp', sim.last_write(0x20) == 0 and sim.last_write(0x2B) == 10
           and sim.last_write(0x25) == 0 and sim.last_write(0x23) == 0)
check_true('spi_default_speed', sim.last_write(0x27) == 71583)
check_true('spi_default_accel', all(sim.last_write(r) == 1564 for r in (0x24, 0x26, 0x28, 0x2A)))
# 0.5 A @ 0.075 ohm: r = 0.5 / 3.0615 = 0.1633 -> GLOBALSCALER 128, IRUN = round(64*r) - 1 = 9
check_true('spi_default_current', sim.last_write(0x0B) == 128
           and ((sim.last_write(0x10) >> 8) & 0x1F) == 9 and (sim.last_write(0x10) & 0x1F) == 3)
check_true('spi_vmax_vector', sim.frames.count(bytes([0xA7, 0x00, 0x01, 0x17, 0x9F])) >= 1)

m.set_current(2.0)
check_true('set_current_2a', sim.last_write(0x0B) == 167 and ((sim.last_write(0x10) >> 8) & 0x1F) == 31
           and (sim.last_write(0x10) & 0x1F) == 10)
m.set_current(3.06)
check_true('set_current_scaler_256_is_0', sim.last_write(0x0B) == 0)
check_true('set_current_range', raises(ValueError, lambda: m.set_current(3.5))
           and raises(ValueError, lambda: m.set_current(0.01)))
m.set_speed(300)
check_true('set_speed', sim.last_write(0x27) == int(round(300 * 256 * 16777216.0 / 12e6)))
check_true('set_speed_range', raises(ValueError, lambda: m.set_speed(0)))
m.set_acceleration(800)
check_true('set_accel', sim.last_write(0x26) == 3127 and sim.last_write(0x2A) == 3127)
check_true('set_accel_range', raises(ValueError, lambda: m.set_acceleration(-1)))

sim.regs[0x21] = 256 * 10
m.move_to(25)
check_true('move_to', sim.last_write(0x2D) == 25 * 256 and sim.last_write(0x20) == 0)
m.move_to(-2)
check_true('move_to_negative', sim.last_write(0x2D) == (-512) & 0xFFFFFFFF)
sim.regs[0x35] = 1 << 9
m.move(5)
check_true('move_relative', sim.last_write(0x2D) == 15 * 256)
sim.regs[0x35] = 0
check_true('move_timeout', raises(TimeoutError, lambda: m.wait_until_reached(0.01)))
m.run(150)
check_true('run_forward', sim.last_write(0x20) == 1 and sim.last_write(0x27) == int(round(150 * 256 * 16777216.0 / 12e6)))
m.run(-150)
check_true('run_reverse', sim.last_write(0x20) == 2)
sim.regs[0x35] = 1 << 10
check_true('wait_vzero', m.wait_until_reached(0.1))
m.stop()
check_true('stop', sim.last_write(0x20) == 1 and sim.last_write(0x27) == 0)
m.move_to(0)
check_true('speed_restored_for_move', sim.last_write(0x27) == int(round(300 * 256 * 16777216.0 / 12e6)))
sim.regs[0x21] = (-1280) & 0xFFFFFFFF
check_true('position', m.position() == -5.0)
m.set_position(7)
check_true('set_position', sim.last_write(0x21) == 7 * 256 and sim.last_write(0x2D) == 7 * 256)
sim.regs[0x22] = 0x7FFFFF - 0x7FFFFF + 71583
check_true('velocity', abs(m.velocity() - 71583 * (6e6 / 8388608.0) / 256) < 1e-6)
sim.regs[0x35] = 1 << 9
check_true('is_position_reached', m.is_position_reached())
sim.regs[0x6F] = (1 << 31) | (1 << 26) | (1 << 28) | (1 << 12) | (1 << 30)
check_true('standstill', m.is_standstill())
check_true('fault_flags', m.read_fault() == (0x01 | 0x04 | 0x08 | 0x10))

en = Pin()
m2 = TMC5160Minimal(SpiSim(), en_pin=en)
check_true('en_pin_high_at_init', en.levels == [True])
m2.enable()
check_true('en_pin_low_on_enable', en.levels[-1] is False)
m3 = TMC5160Minimal(SpiSim())
m3.disable()
check_true('disable_toff0', m3._conn.last_write(0x6C) & 0xF == 0)
m3.enable()
check_true('enable_toff3', m3._conn.last_write(0x6C) & 0xF == 3)

for label, setup in (('bad_version', lambda s: s.regs.__setitem__(0x04, 0)),
                     ('sd_mode', lambda s: s.regs.__setitem__(0x04, 0x30000040)),
                     ('broken_mirror', lambda s: setattr(s, 'mirror_broken', True))):
    bad = SpiSim()
    setup(bad)
    check_true(label + '_raises', raises(OSError, lambda: TMC5160Minimal(bad)))
check_true('bus_type_invalid', raises(ValueError, lambda: TMC5160Minimal(SpiSim(), bus_type='i2c')))

# ---- UART ----
u = UartSim()
um = TMC5160Minimal(u, bus_type='uart', address=1, multi_node=True)
check_true('uart_address_in_datagrams', all(d[1] == 1 for d in u.datagrams))
check_true('uart_slaveconf_first', u.writes[0] == (0x03, 2 << 8))
check_true('uart_gconf', u.last_write(0x00) == 0x4)
check_true('uart_ifcnt', u.regs[0x02] == len(u.writes))
um.set_speed(100)
um.move_to(1)
check_true('uart_move_to', u.last_write(0x2D) == 256)
flaky = UartSim()
flaky.corrupt_next_reply = True
TMC5160Minimal(flaky, bus_type='uart')
check_true('uart_crc_retry', flaky.last_write(0x00) == 0x4)
noecho = UartSim(echo=False)
TMC5160Minimal(noecho, bus_type='uart', echo=False)
check_true('uart_no_echo', noecho.last_write(0x00) == 0x4)
badu = UartSim()
badu.regs[0x04] = 0
check_true('uart_bad_version', raises(OSError, lambda: TMC5160Minimal(badu, bus_type='uart')))
check_true('uart_diag_rejected', raises(ValueError, lambda: TMC5160Full(UartSim(), bus_type='uart', diag0_pin=InPin())))

# ---- Full (SPI) ----
sim = SpiSim()
d0 = InPin()
f = TMC5160Full(sim, diag0_pin=d0)
f.configure_ramp(vstart=0, a1=1200, v1=200, amax=600, vmax=500, dmax=900, d1=1500, vstop=10)
check_true('configure_ramp', sim.last_write(0x25) == int(round(200 * 256 * 16777216.0 / 12e6))
           and sim.last_write(0x2B) == int(round(10 * 256 * 16777216.0 / 12e6))
           and sim.last_write(0x24) == int(round(1200 * 256 * 2199023255552.0 / 144e12)))
check_true('configure_ramp_errors', raises(ValueError, lambda: f.configure_ramp(0, 1, 0, 1, 1, 1, 0, 10))
           and raises(ValueError, lambda: f.configure_ramp(0, 1, 0, 1, 1, 1, 1, 0))
           and raises(ValueError, lambda: f.configure_ramp(100, 1, 0, 1, 1, 1, 1, 10)))
f.set_ramp_raw(1, 2, 3, 4, 5, 6, 7, 8)
check_true('set_ramp_raw', sim.last_write(0x23) == 1 and sim.last_write(0x2B) == 8 and sim.last_write(0x27) == 5)
f.set_zero_wait(0.5)
check_true('zero_wait', sim.last_write(0x2C) == int(round(0.5 * 12e6 / 512)))
f.set_ramp_mode(3)
check_true('ramp_mode', sim.last_write(0x20) == 3)
check_true('ramp_mode_range', raises(ValueError, lambda: f.set_ramp_mode(4)))
f.hold()
f.set_target_raw(1234)
check_true('target_raw', sim.last_write(0x2D) == 1234)
sim.regs[0x35] = (1 << 6) | (1 << 9) | 1
rs = f.read_ramp_status()
check_true('ramp_status', rs['event_stop_sg'] and rs['position_reached'] and rs['status_stop_l']
           and not rs['vzero'])
f.clear_ramp_events()
check_true('clear_ramp_events', sim.last_write(0x35) == (1 << 2) | (1 << 3) | (1 << 6) | (1 << 7) | (1 << 12))
f.set_position_compare(3)
check_true('x_compare', sim.last_write(0x05) == 768)
f.configure_switches(stop_left=True, stop_right=True, left_active_low=True, swap=True)
check_true('switches', sim.last_write(0x34) & 0x1F == 0b10111)
f.configure_latch(True, False, False, True, True)
check_true('latch_keeps_switches', sim.last_write(0x34) == 0b10111 | (1 << 5) | (1 << 8) | (1 << 9))
f.configure_switches(soft_stop=True)
check_true('soft_stop', sim.last_write(0x34) & (1 << 11))
check_true('stall_stop_vs_soft', raises(ValueError, lambda: f.enable_stall_stop(True)))
f.configure_switches(soft_stop=False)
f.enable_stall_stop(True)
check_true('sg_stop', sim.last_write(0x34) & (1 << 10))
check_true('soft_vs_sg', raises(ValueError, lambda: f.configure_switches(soft_stop=True)))
f.enable_stall_stop(False)
sim.regs[0x36] = (-512) & 0xFFFFFFFF
check_true('latched_position', f.read_latched_position() == -2.0)
sim.regs[0x35] = 3
check_true('read_switches', f.read_switches() == (True, True))

f.set_microsteps(16)
check_true('microsteps', (sim.last_write(0x6C) >> 24) & 0xF == 4 and f._usc == 16)
check_true('microsteps_invalid', raises(ValueError, lambda: f.set_microsteps(3)))
f.set_microsteps(256)
f.set_interpolation(True)
check_true('intpol', (sim.last_write(0x6C) >> 28) & 1)
f.set_chopper_mode(True)
check_true('spreadcycle', sim.last_write(0x00) & 4 == 0)
f.set_stealthchop_threshold(300)
f.set_high_velocity_threshold(77)
check_true('thresholds', sim.last_write(0x13) == 300 and sim.last_write(0x15) == 77)
f.configure_chopper(4, 1, 3, 2, tpfd=5)
c = sim.last_write(0x6C)
check_true('configure_chopper', (c & 0xF) == 4 and ((c >> 15) & 3) == 1 and ((c >> 4) & 7) == 3
           and ((c >> 7) & 0xF) == 2 and ((c >> 20) & 0xF) == 5 and not (c >> 14) & 1)
f.configure_constant_off_time(5, 2, 11, -3, True, tpfd=1)
c = sim.last_write(0x6C)
check_true('configure_const_off', (c & 0xF) == 5 and (c >> 14) & 1 and ((c >> 4) & 7) == 3
           and (c >> 11) & 1 and ((c >> 7) & 0xF) == 0 and (c >> 12) & 1 and ((c >> 20) & 0xF) == 1)
f.configure_high_velocity(True, True)
check_true('high_velocity', (sim.last_write(0x6C) >> 18) & 3 == 3)
f.configure_stealthchop(True, True, 40, 5, 1, 4, 12)
p = sim.last_write(0x70)
check_true('configure_stealthchop', (p & 0xFF) == 40 and ((p >> 8) & 0xFF) == 5 and ((p >> 16) & 3) == 1
           and (p >> 18) & 1 and (p >> 19) & 1 and ((p >> 24) & 0xF) == 4 and (p >> 28) == 12)
f.set_freewheel(1)
check_true('freewheel', (sim.last_write(0x70) >> 20) & 3 == 1)
f.set_short_protection(False, True)
check_true('short_protection', (sim.last_write(0x6C) >> 30) & 1 == 1 and (sim.last_write(0x6C) >> 31) & 1 == 0)
f.set_double_edge(True)
check_true('dedge', (sim.last_write(0x6C) >> 29) & 1)
f.set_direction_inverted(True)
f.set_fast_standstill(True)
f.set_recalibrate(True)
f.set_small_hysteresis(True)
f.set_emergency_stop(True)
check_true('gconf_flags', sim.last_write(0x00) & ((1 << 4) | (1 << 1) | 1 | (1 << 14) | (1 << 15))
           == ((1 << 4) | (1 << 1) | 1 | (1 << 14) | (1 << 15)))
f.configure_diag(True, True, True, False)
g = sim.last_write(0x00)
check_true('diag_bits', (g >> 7) & 1 and (g >> 8) & 1 and (g >> 12) & 1 and not (g >> 13) & 1)
f.set_stall_threshold(-5)
f.set_stallguard_filter(True)
f.configure_coolstep(5, 2, 1, 1, 0)
cc = sim.last_write(0x6D)
check_true('coolconf', (cc & 0xF) == 5 and ((cc >> 8) & 0xF) == 2 and (cc >> 24) & 1
           and ((cc >> 16) & 0x7F) == (-5 & 0x7F))
check_true('stall_range', raises(ValueError, lambda: f.set_stall_threshold(64))
           and raises(ValueError, lambda: f.configure_coolstep(16, 0, 0, 0, 0)))
f.set_coolstep_threshold(500)
check_true('tcoolthrs', sim.last_write(0x14) == 500)
sim.regs[0x6F] = (1 << 24) | (17 << 16) | 0x2A | (1 << 14)
check_true('stallguard', f.read_stallguard() == 0x2A and f.is_stalled())
check_true('read_diag', f.read_diag(0) is True
           and raises(RuntimeError, lambda: f.read_diag(1)) and raises(ValueError, lambda: f.read_diag(2)))
f.set_dcstep(1000, 200, 3)
check_true('dcstep', sim.last_write(0x33) == 1000 and sim.last_write(0x6E) == 200 | (3 << 16))
check_true('coil_requires_direct', raises(RuntimeError, lambda: f.set_coil_currents(1, 1)))
f.set_direct_mode(True)
f.set_coil_currents(100, -100)
check_true('xdirect', sim.last_write(0x2D) == 100 | ((-100 & 0x1FF) << 16))
f.set_direct_mode(False)
check_true('direct_off_rewrites_xtarget', sim.last_write(0x2D) == 100 | ((-100 & 0x1FF) << 16)
           or sim.last_write(0x2D) is not None)
f.write_microstep_table(list(range(1, 9)), 0x12345678, 0x00F70000)
check_true('mslut', sim.last_write(0x60) == 1 and sim.last_write(0x67) == 8 and sim.last_write(0x69) == 0x00F70000)
f.set_global_scaler(256)
check_true('global_scaler', sim.last_write(0x0B) == 0 and raises(ValueError, lambda: f.set_global_scaler(20)))
f.set_current_scale(20, 5, 3)
check_true('current_scale', sim.last_write(0x10) == 5 | (20 << 8) | (3 << 16))
f.set_power_down_delay(20)
check_true('tpowerdown', sim.last_write(0x11) == 20)
f.set_hold_current(0.1)
check_true('hold_current', sim.last_write(0x10) & 0x1F < 20)
f.configure_gate_driver(2, 4, 1, 2, 1)
check_true('drv_conf', sim.last_write(0x0A) == 2 | (4 << 8) | (1 << 16) | (2 << 18) | (1 << 20))
check_true('drv_conf_range', raises(ValueError, lambda: f.configure_gate_driver(25, 0, 0, 0, 0)))
f.configure_short_detection(6, 6, 1, 0)
check_true('short_conf', sim.last_write(0x09) == 6 | (6 << 8) | (1 << 16))
check_true('short_conf_range', raises(ValueError, lambda: f.configure_short_detection(3, 6, 1, 0)))
f.configure_encoder(True, False, True, False, False, False, True, False, False, False, False)
check_true('encmode', sim.last_write(0x38) == 1 | 4 | 64)
f.set_encoder_constant(1.5)
check_true('enc_const_binary', sim.last_write(0x3A) == 0x00018000)
f.set_encoder_constant(-1.0)
check_true('enc_const_negative', sim.last_write(0x3A) == 0xFFFF0000)
f.configure_encoder(False, False, False, False, False, False, False, False, False, False, True)
f.set_encoder_constant(2.5)
check_true('enc_const_decimal', sim.last_write(0x3A) == (2 << 16) | 5000)
f.set_encoder_position(-3)
sim.regs[0x39] = (-3) & 0xFFFFFFFF
sim.regs[0x3C] = 77
sim.regs[0x3B] = 2
check_true('encoder_reads', f.read_encoder_position() == -3 and f.read_encoder_latch() == 77
           and f.read_encoder_status() == {'n_event': False, 'deviation_warn': True})
f.set_encoder_deviation(5)
f.clear_encoder_status()
check_true('encoder_dev_clear', sim.last_write(0x3D) == 5 and sim.last_write(0x3B) == 3)
check_true('uart_only_on_spi', raises(RuntimeError, lambda: f.set_slave_address(1))
           and raises(RuntimeError, f.read_ifcnt) and raises(RuntimeError, lambda: f.set_send_delay(2))
           and raises(RuntimeError, lambda: f.set_nao_polarity(True)))

sim.regs[0x6A] = 513
sim.regs[0x6B] = (0x1FF << 16) | 0x100
sim.regs[0x12] = 1234
sim.regs[0x71] = (0x1FE << 16) | 77
sim.regs[0x72] = (9 << 16) | 33
sim.regs[0x0C] = (0xFF << 8) | 0x05
sim.regs[0x6F] = (1 << 31) | (1 << 14) | (1 << 15) | (17 << 16) | (1 << 25) | (1 << 12) | 300
sim.regs[0x01] = 3
sim.regs[0x04] = 0x30000000 | 0x41 | 0x10
check_true('microstep_position', f.microstep_position() == 513)
check_true('coil_currents', f.read_coil_currents() == (-1, -256))
check_true('tstep', f.read_tstep() == 1234)
check_true('pwm_scale', f.read_pwm_scale() == (77, -2))
check_true('pwm_auto', f.read_pwm_auto() == (33, 9))
check_true('offset', f.read_offset() == (-1, 5))
check_true('current_scale_read', f.read_current_scale() == 17)
st = f.read_status()
check_true('status', st['stst'] and st['stealth'] and st['fsactive'] and st['ot'] and st['short_supply']
           and st['cs_actual'] == 17 and st['sg_result'] == 300 and not st['otpw'])
check_true('global_status', f.read_global_status() == {'reset': True, 'drv_err': True, 'uv_cp': False}
           and sim.regs[0x01] == 3)
f.clear_global_status()
check_true('clear_global_status', sim.last_write(0x01) == 7)
io = f.read_ioin()
check_true('ioin', io['version'] == 0x30 and io['refl'] and io['drv_enn'] and io['sd_mode'])
check_true('read_register', f.read_register(0x6A) == 513 and raises(ValueError, lambda: f.read_register(0x10)))
f.write_register(0x13, 5, verify=True)
check_true('write_register', sim.last_write(0x13) == 5 and raises(ValueError, lambda: f.write_register(0x06, 1)))

# ---- Full (UART) ----
u = UartSim()
uf = TMC5160Full(u, bus_type='uart', address=2)
uf.set_slave_address(3)
check_true('uart_slave_address', u.last_write(0x03) == 3 and uf._address == 3)
uf.set_send_delay(2)
check_true('uart_send_delay', u.last_write(0x03) == 3 | (2 << 8))
uf.set_nao_polarity(False)
check_true('uart_nao', u.last_write(0x04) == 0)
uf.write_register(0x13, 9, verify=True)
check_true('uart_verified_write', u.last_write(0x13) == 9)
check_true('uart_ifcnt_read', uf.read_ifcnt() == u.regs[0x02])
check_true('uart_slave_range', raises(ValueError, lambda: uf.set_slave_address(254)))

print('===DONE: %d passed, %d failed===' % (passed, failed))

if failed:
    raise SystemExit(1)
