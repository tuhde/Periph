import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.adc_dac.mcp4xxx import Mcp4xxxFull, Mcp4xxxMinimal, PARTS, default_address

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


class Pin:
    def __init__(self, log):
        self.log = log

    def set(self, high):
        self.log.append('HVC1' if high else 'HVC0')


class ChipMock(I2CConnectionMock):
    """Models the MCP4XXX command protocol: 16 nine-bit memory cells, inc/dec, reads of two bytes."""

    def __init__(self, max_code=128, channels=2, nv=False):
        super().__init__()
        self.mem = {a: 0 for a in range(16)}
        self.mem[0] = self.mem[1] = max_code // 2
        self.mem[2] = self.mem[3] = max_code // 2
        self.mem[4] = 0x1FF
        self.mem[5] = 0x1F0
        self.max_code = max_code
        self.log = []
        self.status = 0x1F0

    def write(self, data):
        data = bytes(data)
        self.writes.append(data)
        self.log.append(data)
        i = 0
        while i < len(data):
            cmd = data[i]
            addr, op = cmd >> 4, (cmd >> 2) & 3
            if op == 0:
                self.mem[addr] = ((cmd & 1) << 8) | data[i + 1]
                i += 2
            else:
                if addr in (0, 1):
                    w = self.mem[addr]
                    self.mem[addr] = min(self.max_code, w + 1) if op == 1 else max(0, w - 1)
                i += 1

    def write_read(self, data, n):
        self.writes.append(bytes(data))
        cmd = data[0]
        value = self.mem[cmd >> 4] if cmd >> 4 != 5 else self.status
        return bytes([(value >> 8) & 1, value & 0xFF])[:n]


def make(part, **kw):
    desc = PARTS[part]
    conn = ChipMock(desc['max_code'], desc['channels'], desc['nv'])
    return conn, Mcp4xxxFull(conn, part, **kw)


# Descriptor table / address helpers.
check_true('sixteen_parts', len(PARTS) == 16)
check_true('default_address', default_address('mcp4531') == 0x2E and default_address('MCP4632') == 0x58 and default_address('MCP4661') == 0x28)
try:
    Mcp4xxxMinimal(I2CConnectionMock(), 'MCP9999')
    check_true('unknown_part_raises', False)
except ValueError:
    check_true('unknown_part_raises', True)

# Init performs only reads (writes log only the read command bytes).
conn, chip = make('MCP4652')
check_true('init_reads_only', conn.writes == [b'\x0C', b'\x1C', b'\x4C'])
conn, chip = make('MCP4662')
check_true('init_nv_reads_status', conn.writes == [b'\x0C', b'\x1C', b'\x4C', b'\x5C'])

# Properties.
check_true('props_4652', (chip.channels(), chip.steps(), chip.is_potentiometer(), chip.is_nonvolatile()) == (2, 257, False, True))
c7, p7 = make('MCP4531')
check_true('props_4531', (p7.channels(), p7.steps(), p7.is_potentiometer(), p7.is_nonvolatile()) == (1, 129, True, False))

# set_wiper byte format: 7-bit and 8-bit (D8 in command byte).
c7.writes.clear()
p7.set_wiper(100)
check_true('set_wiper_7bit', c7.writes[-1] == bytes([0x00, 100]))
p7.set_wiper(999)
check_true('set_wiper_clamps_128', c7.writes[-1] == bytes([0x00, 128]))
p7.set_wiper(-3)
check_true('set_wiper_clamps_0', c7.writes[-1] == bytes([0x00, 0]))
conn, c8 = make('MCP4652')
c8.set_wiper(256, 1)
check_true('set_wiper_8bit_d8', conn.writes[-1] == bytes([0x11, 0x00]))
c8.set_wiper(255, 0)
check_true('set_wiper_8bit_255', conn.writes[-1] == bytes([0x00, 255]))
check_true('get_wiper_8bit', c8.get_wiper(1) == 256 and conn.writes[-1] == bytes([0x1C]))
for call in (lambda: p7.set_wiper(1, 1), lambda: p7.get_wiper(1), lambda: c8.set_wiper(1, 2)):
    try:
        call()
        check_true('bad_channel_raises', False)
    except ValueError:
        check_true('bad_channel_raises', True)

# Resistance model (RAB 10k, RW 75, 7-bit).
p7.set_wiper(64)
check_true('get_resistance', abs(p7.get_resistance() - 5075.0) < 1e-9)
p7.set_wiper(0)
check_true('get_resistance_zero', abs(p7.get_resistance() - 75.0) < 1e-9)
p7.set_resistance(5075.0)
check_true('set_resistance', p7.get_wiper() == 64)
p7.set_resistance(1e9)
check_true('set_resistance_clamps', p7.get_wiper() == 128)
check_true('resistance_wa', abs(p7.get_resistance_wa() - 75.0) < 1e-9)
p7.set_resistance_wa(5075.0)
check_true('set_resistance_wa', p7.get_wiper() == 64)
check_true('step', abs(p7.get_resistance_step() - 10000.0 / 128) < 1e-9)
conn, rheo = make('MCP4532')
try:
    rheo.get_resistance_wa()
    check_true('rheostat_wa_raises', False)
except ValueError:
    check_true('rheostat_wa_raises', True)

# Ratio, set_both.
p7.set_ratio(0.5)
check_true('set_ratio', p7.get_wiper() == 64 and abs(p7.get_ratio() - 0.5) < 1e-9)
conn, d = make('MCP4631')
d.set_both(10, 20)
check_true('set_both', conn.mem[0] == 10 and conn.mem[1] == 20)
try:
    p7.set_both(1, 2)
    check_true('set_both_single_raises', False)
except ValueError:
    check_true('set_both_single_raises', True)

# increment / decrement: one continuous transaction, saturating.
conn, d = make('MCP4631')
d.set_wiper(120, 1)
conn.log.clear()
check_true('increment_saturates', d.increment(10, 1) == 128)
check_true('increment_one_write', conn.log == [bytes([0x14] * 10)])
d.set_wiper(5)
check_true('decrement_saturates', d.decrement(10) == 0)
check_true('increment_single', d.increment() == 1 and conn.log[-1] == b'\x04')

# Shutdown / terminals via TCON read-modify-write.
conn, d = make('MCP4631')
d.shutdown(1)
check_true('shutdown_ch1_tcon', conn.mem[4] == 0x1FF & ~0x80 and d.is_shutdown(1) and not d.is_shutdown(0))
d.resume(1)
check_true('resume_tcon', conn.mem[4] == 0x1FF)
d.shutdown(0)
check_true('shutdown_ch0_tcon', conn.mem[4] == 0x1FF & ~0x08)
d.resume(0)
d.set_terminals(a=True, w=False, b=True, channel=1)
check_true('terminals_ch1', conn.mem[4] == 0x1FF & ~0x20 and d.get_terminals(1) == (True, False, True) and d.get_terminals(0) == (True, True, True))
d.set_general_call(False)
check_true('general_call_off', conn.mem[4] & 0x100 == 0 and d.get_tcon() == conn.mem[4])
d.set_general_call(True)
check_true('general_call_on', conn.mem[4] & 0x100 != 0)

# reset: volatile -> mid-scale, TCON 0x1FF.
d.set_wiper(3, 0)
d.shutdown(1)
d.reset()
check_true('reset_volatile', conn.mem[0] == 64 and conn.mem[1] == 64 and conn.mem[4] == 0x1FF)

# raw register access.
check_true('read_register', d.read_register(4) == 0x1FF)
d.write_register(0, 77)
check_true('write_register', conn.mem[0] == 77)
for call in (lambda: d.write_register(5, 0), lambda: d.read_register(2), lambda: d.read_register(16)):
    try:
        call()
        check_true('bad_register_raises', False)
    except ValueError:
        check_true('bad_register_raises', True)

# Non-volatile operations raise on a volatile part.
for call in (d.read_status, d.wait_ready, d.get_nv_wiper, d.store_wiper, lambda: d.read_data(0), lambda: d.set_write_protect(True)):
    try:
        call()
        check_true('nv_op_raises_on_volatile', False)
    except ValueError:
        check_true('nv_op_raises_on_volatile', True)

# --- Non-volatile part ---
pins = []
conn, n = make('MCP4662', hvc=Pin(pins))
st = n.read_status()
check_true('status_idle', st == {'eeprom_busy': False, 'wiper_lock': [False, False], 'write_protect': False})
conn.status = 0x1F0 | 0x08 | 0x02 | 0x01
st = n.read_status()
check_true('status_flags', st['eeprom_busy'] and st['wiper_lock'] == [True, False] and st['write_protect'])
check_true('is_locked', n.is_wiper_locked(0) and not n.is_wiper_locked(1) and n.is_write_protected())
conn.status = 0x1F0
try:
    conn.status = 0x1F0 | 0x08
    n.wait_ready(timeout_ms=3)
    check_true('wait_ready_timeout', False)
except TimeoutError:
    check_true('wait_ready_timeout', True)
conn.status = 0x1F0
n.wait_ready()

n.set_nv_wiper(200, 1)
check_true('set_nv_wiper', conn.mem[3] == 200 and n.get_nv_wiper(1) == 200)
n.set_wiper(150, 0)
conn.log.clear()
check_true('store_wiper', n.store_wiper(0) == 150 and conn.mem[2] == 150)
n.write_data(3, 0x1AB)
check_true('write_data', conn.mem[9] == 0x1AB and n.read_data(3) == 0x1AB)
check_true('write_data_cmd', bytes([0x91, 0xAB]) in conn.log)
for call in (lambda: n.read_data(10), lambda: n.write_data(-1, 0)):
    try:
        call()
        check_true('bad_index_raises', False)
    except ValueError:
        check_true('bad_index_raises', True)
check_true('nv_reset', (n.set_wiper(7, 0), n.reset(), conn.mem[0] == 150 and conn.mem[1] == 200)[2])

# High-voltage commands: exact single command bytes, pin asserted around the write.
conn.log.clear()
n.set_wiper_lock(True, 0)
check_true('wl_enable_0', conn.log[0] == b'\x28' and pins[-2:] == ['HVC1', 'HVC0'])
n.set_wiper_lock(False, 1)
check_true('wl_disable_1', b'\x34' in conn.log)
n.set_wiper_lock(True, 1)
check_true('wl_enable_1', b'\x38' in conn.log)
n.set_write_protect(True)
check_true('wp_enable', b'\xF8' in conn.log)
n.set_write_protect(False)
check_true('wp_disable', b'\xF4' in conn.log)
conn2, nohv = make('MCP4562')
try:
    nohv.set_wiper_lock(True)
    check_true('hv_needs_pin', False)
except ValueError:
    check_true('hv_needs_pin', True)

# set_rab changes the model without traffic.
n_writes = len(conn.writes)
n.set_rab(50000.0, 100.0)
check_true('set_rab_no_traffic', len(conn.writes) == n_writes)
n.set_wiper(128, 0)
check_true('set_rab_resistance', abs(n.get_resistance(0) - (25000.0 + 100.0)) < 1e-9)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
