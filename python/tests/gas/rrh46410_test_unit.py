import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.gas.rrh46410 import RRH46410Minimal, RRH46410Full, RRH46410Error

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


def cs(data):
    return (~sum(data)) & 0xFF


class RRH46410Sim(I2CConnectionMock):
    """Simulates the RRH46410 request/response protocol with checksums."""

    def __init__(self):
        super().__init__()
        self.requests = []
        self.response = None
        self.mode = 0
        self.counter = 0
        self.has_result = False
        self.pid = 0x2310
        self.bad_checksum = False
        self.status_override = None
        self.gpio = 0x0C
        self.humidity = None
        self.busy = 0
        self.int_pin = None

    def write(self, data):
        data = bytes(data)
        self.writes.append(data)
        self._maybe_fail_write()
        cmd, params = data[0], data[1:-1]
        self.requests.append((cmd, params, (sum(data) & 0xFF) == 0xFF))
        self.response = self.respond(cmd, params)

    def respond(self, cmd, p):
        status = 0
        out = b''
        if cmd == 0x00:
            out = bytes([self.pid >> 8, self.pid & 0xFF, 1, 2, 3])
        elif cmd == 0x01:
            out = b'\x01\x02\x03\x04\x05\x06'
        elif cmd == 0x10:
            out = bytes([self.mode])
            if self.mode == 0x80 and self.busy:
                self.busy -= 1
                if self.busy == 0:
                    self.mode = 0
        elif cmd == 0x11:
            self.mode = p[0]
            self.counter = 0
            self.has_result = False
        elif cmd == 0x12:
            self.humidity = p[0]
        elif cmd == 0x18:
            if not self.has_result:
                status = 0x10
            if self.mode == 0x05:
                out = bytes([self.counter, 0x01, 0xF4, 0x0B, 0xB8])
            else:
                out = bytes([self.counter, 25, 0x00, 0x32, 0x01, 0x2C, 0x02, 0x58, 3])
        elif cmd == 0x20:
            self.gpio_dir = p[0]
        elif cmd == 0x21:
            out = bytes([self.gpio])
        elif cmd == 0x22:
            self.gpio |= p[0]
        elif cmd == 0x23:
            self.gpio &= ~p[0]
        elif cmd == 0x8F:
            self.mode = 0
            return None
        else:
            status = 0x80
        if self.status_override is not None:
            status = self.status_override
        frame = bytes([status]) + out
        c = cs(frame)
        if self.bad_checksum:
            c ^= 1
        return frame + bytes([c])

    def sample(self, n=1):
        self.has_result = True
        self.counter = (self.counter + n) & 0xFF

    def read(self, n):
        if self._fail_reads > 0:
            self._fail_reads -= 1
            raise OSError(5, 'I2C NACK (mock)')
        if self.response is None:
            return b'\xff' * n
        r = self.response[:n]
        return r + b'\xff' * (n - len(r))


# --- checksum and init ---
sim = RRH46410Sim()
m = RRH46410Minimal(sim)
m.init()
check_true('init_product_id_request', sim.writes[0] == b'\x00\xff')
check_true('init_set_mode_request', sim.writes[1] == bytes([0x11, 0x01, cs(b'\x11\x01')]))
check_true('init_request_checksums', all(ok for _, _, ok in sim.requests))
check_true('init_mode_iaq', sim.mode == 0x01)

sim = RRH46410Sim()
sim.pid = 0x1234
try:
    RRH46410Minimal(sim).init()
    check_true('init_wrong_pid_raises', False)
except RRH46410Error:
    check_true('init_wrong_pid_raises', True)

sim = RRH46410Sim()
sim.fail_next_writes(3)
RRH46410Minimal(sim).init()
check_true('init_retries_until_ready', sim.mode == 0x01)

sim = RRH46410Sim()
sim.fail_next_writes(1000)
try:
    RRH46410Minimal(sim).init()
    check_true('init_timeout_raises', False)
except RRH46410Error:
    check_true('init_timeout_raises', True)
sim.fail_next_writes(0)

# --- read ---
sim = RRH46410Sim()
m = RRH46410Minimal(sim)
m.init()
sim.sample(5)
r = m.read()
check_true('read_iaq', abs(r['iaq'] - 2.5) < 1e-9)
check_true('read_tvoc', abs(r['tvoc_mg_m3'] - 0.5) < 1e-9)
check_true('read_etoh', abs(r['etoh_ppm'] - 3.0) < 1e-9)
check_true('read_eco2', r['eco2_ppm'] == 600)
check_true('read_rel_iaq', r['relative_iaq'] == 30)
check_true('read_sample', r['sample'] == 5)
check_true('read_not_warmed_up', r['warmed_up'] is False)
check_true('read_request', sim.writes[-1] == b'\x18\xe7')
sim.sample(95)
r = m.read()
check_true('read_warmed_up_after_100', r['warmed_up'] is True and r['sample'] == 100)
sim.sample(200)   # counter wraps 100 -> 44
r = m.read()
check_true('read_counter_wrap', r['sample'] == 44 and r['warmed_up'] is True)

sim = RRH46410Sim()
m = RRH46410Minimal(sim)
m.init()
m._POLL_MS = 1
try:
    m._wait_sample(0.01)
    ok = not m._wait_sample(0.01)
except RRH46410Error:
    ok = False
check_true('wait_no_result_false', ok)

sim.sample(1)
sim.bad_checksum = True
try:
    m.read()
    check_true('bad_checksum_raises', False)
except RRH46410Error:
    check_true('bad_checksum_raises', True)
sim.bad_checksum = False

sim.status_override = 0x0A
try:
    m.read()
    check_true('damage_status_raises', False)
except RRH46410Error as e:
    check_true('damage_status_raises', e.status == 0x0A)
sim.status_override = None

# --- Full ---
sim = RRH46410Sim()
f = RRH46410Full(sim)
f.init()
f._POLL_MS = 1
pid, fw = f.product_id()
check_true('product_id', pid == 0x2310 and fw == (1, 2, 3))
check_true('tracking_number', f.tracking_number() == b'\x01\x02\x03\x04\x05\x06')
check_true('get_mode', f.get_mode() == RRH46410Full.MODE_IAQ_2ND_GEN)
check_true('read_results_none_before_sample', f.read_results() is None)
check_true('status_not_complete', f.status()['not_complete'] is True)
sim.sample(3)
res = f.read_results()
check_true('read_results', res['sample'] == 3 and res['eco2_ppm'] == 600)
check_true('status_ok', f.status()['raw'] == 0 and f.status()['mode_error'] == 0)
check_true('wait_for_sample_false', f.wait_for_sample(0.005) is False)
sim.sample()
check_true('poll_interrupt_true', f.poll_interrupt() is True)
check_true('wait_for_sample_true', f.wait_for_sample(0.1) is True)
check_true('poll_interrupt_false', f.poll_interrupt() is False)

f.set_humidity(50)
check_true('set_humidity_code', sim.humidity == 128)
f.set_humidity(100)
check_true('set_humidity_100', sim.humidity == 255)
try:
    f.set_humidity(101)
    check_true('set_humidity_range', False)
except ValueError:
    check_true('set_humidity_range', True)

f.set_mode(RRH46410Full.MODE_PBAQ)
check_true('set_mode_pbaq', sim.mode == 0x05)
check_true('sample_period_pbaq', f.sample_period() == 5.0)
check_true('warmup_pbaq', f.warmup_samples() == 60)
check_true('sample_period_ulp', f.sample_period(RRH46410Full.MODE_ULP) == 90.0)
check_true('warmup_ulp', f.warmup_samples(RRH46410Full.MODE_ULP) == 10)
check_true('sample_period_iaq', f.sample_period(RRH46410Full.MODE_IAQ_2ND_GEN) == 3.0)
check_true('warmup_iaq', f.warmup_samples(RRH46410Full.MODE_IAQ_2ND_GEN) == 100)
sim.sample(2)
res = f.read_results()
check_true('pbaq_tvoc', abs(res['tvoc_mg_m3'] - 0.5) < 1e-9)
check_true('pbaq_etoh', abs(res['etoh_ppm'] - 3.0) < 1e-9)
check_true('pbaq_keys', 'iaq' not in res and 'eco2_ppm' not in res)
check_true('pbaq_request_len', sim.writes[-1] == b'\x18\xe7')
try:
    f.set_mode(0x04)
    check_true('set_mode_invalid', False)
except ValueError:
    check_true('set_mode_invalid', True)

check_true('label_very_good', RRH46410Full.air_quality_label(1.5) == 'very good')
check_true('label_good', RRH46410Full.air_quality_label(2.0) == 'good')
check_true('label_medium', RRH46410Full.air_quality_label(3.9) == 'medium')
check_true('label_poor', RRH46410Full.air_quality_label(4.0) == 'poor')
check_true('label_bad', RRH46410Full.air_quality_label(5.0) == 'bad')

f.config_gpio(0x03)
check_true('config_gpio', sim.gpio_dir == 0x03)
f.set_gpio(0x01)
check_true('set_gpio', f.get_gpio() & 0x01)
f.clear_gpio(0x01)
check_true('clear_gpio', not f.get_gpio() & 0x01)
try:
    f.set_gpio(4)
    check_true('gpio_mask_range', False)
except ValueError:
    check_true('gpio_mask_range', True)

f.suspend()
check_true('suspend', sim.mode == 0x00)

try:
    f.clean_sensor()
    check_true('clean_requires_confirm', False)
except ValueError:
    check_true('clean_requires_confirm', sim.mode == 0x00)

out = f.send_command(0x21, b'', 1)
check_true('send_command', out == bytes([sim.gpio]))
f.send_command(0x55)
check_true('send_command_invalid_status', f.status()['invalid_command'] is True)

f.set_mode(RRH46410Full.MODE_IAQ_2ND_GEN)
f.reset()
check_true('reset_request', b'\x8f\x70' in sim.writes)
check_true('reset_suspend', f.sample_period() == 0.0 and sim.mode == 0)

# --- interrupt ---
class Pin:
    RISING = 1
    FALLING = 2

    def __init__(self):
        self.handler = None
        self.trigger = None
        self.high = False

    def on_edge(self, handler, trigger):
        self.handler = handler
        self.trigger = trigger

    def off_edge(self, handler):
        self.handler = None

    def is_high(self):
        return self.high


sim = RRH46410Sim()
f = RRH46410Full(sim)
f.init()
pin = Pin()
hits = []
f.on_interrupt(hits.append, pin)
check_true('on_interrupt_registers_rising', pin.handler is not None and pin.trigger == Pin.RISING)
sim.sample()
pin.handler(pin)
check_true('on_interrupt_callback', len(hits) == 1 and hits[0]['sample'] == 1)
pin.handler(pin)
check_true('on_interrupt_no_duplicate', len(hits) == 1)
f.off_interrupt()
check_true('off_interrupt', pin.handler is None)

sim = RRH46410Sim()
sim.int_pin = Pin()
f = RRH46410Full(sim)
f.init()
check_true('poll_interrupt_pin_low', f.poll_interrupt() is False)
sim.int_pin.high = True
check_true('poll_interrupt_pin_high', f.poll_interrupt() is True)

print('===DONE: %d passed, %d failed===' % (passed, failed))
sys.exit(0 if failed == 0 else 1)
