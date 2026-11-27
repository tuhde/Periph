import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.rfid.pn532 import PN532Minimal, PN532Full, PN532Error

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


ACK = b'\x00\x00\xff\x00\xff\x00'
NACK = b'\x00\x00\xff\xff\x00\x00'


def frame(tfi, data):
    body = bytes([tfi]) + bytes(data)
    return b'\x00\x00\xff' + bytes([len(body), (0x100 - len(body)) & 0xFF]) + body + \
        bytes([(0x100 - (sum(body) & 0xFF)) & 0xFF, 0x00])


class PN532Sim(I2CConnectionMock):
    """Simulates the PN532 I²C frame protocol: ACK, response, NACK resend,
    ACK abort, status byte, error frame."""

    def __init__(self):
        super().__init__()
        self.queue = []
        self.last = None
        self.commands = []
        self.reads = []
        self.card = True
        self.uid = b'\x04\x11\x22\x33\x44\x55\x66'
        self.no_ack = False
        self.no_response = False
        self.error_frame = False
        self.bad_dcs = False
        self.ic = 0x32
        self.status = 0
        self.regs = {}
        self.list_response = None

    def write(self, data):
        data = bytes(data)
        self.writes.append(data)
        self._maybe_fail_write()
        if data == ACK:
            self.queue = []
            return
        if data == NACK:
            if self.last is not None:
                self.queue.append(self.last)
            return
        ln = data[3]
        cmd = data[6]
        params = data[7:5 + ln]
        self.commands.append((cmd, params))
        if self.no_ack:
            return
        self.queue = [ACK]
        if self.no_response:
            return
        if self.error_frame:
            self.queue.append(b'\x00\x00\xff\x01\xff\x7f\x81\x00')
            return
        resp = frame(0xD5, bytes([cmd + 1]) + self.respond(cmd, params))
        if self.bad_dcs:
            resp = resp[:-2] + bytes([(resp[-2] + 1) & 0xFF, 0])
        self.queue.append(resp)

    def respond(self, cmd, p):
        if cmd == 0x02:
            return bytes([self.ic, 0x01, 0x06, 0x07])
        if cmd == 0x04:
            return bytes([0x00, 0x01, 0x01, 0x01, 0x00, 0x00, 0x10, 0x01])
        if cmd == 0x4A:
            if self.list_response is not None:
                return self.list_response
            if not self.card:
                return b'\x00'
            return bytes([1, 1, 0x00, 0x44, 0x00, len(self.uid)]) + self.uid
        if cmd in (0x52, 0x54, 0x44, 0x16):
            return bytes([self.status])
        if cmd == 0x40:
            if self.status:
                return bytes([self.status])
            if p[1] == 0x30:
                return b'\x00' + bytes(range(p[2], p[2] + 16))
            return b'\x00'
        if cmd == 0x42:
            return b'\x00' + p[::-1]
        if cmd == 0x06:
            return bytes([self.regs.get((p[0] << 8) | p[1], 0)])
        if cmd == 0x08:
            self.regs[(p[0] << 8) | p[1]] = p[2]
            return b''
        if cmd == 0x00:
            return p if p[0] == 0 else b'\x00'
        return b''

    def read(self, n):
        self.reads.append(n)
        if self._fail_reads > 0:
            self._fail_reads -= 1
            raise OSError(5, 'I2C NACK (mock)')
        if not self.queue:
            return bytes(n)
        if n == 1:
            return b'\x01'
        item = self.queue.pop(0)
        if item != ACK:
            self.last = item
        out = b'\x01' + item[:n - 1]
        return out + b'\xff' * (n - len(out))


# --- frame format ---
check_true('frame_get_firmware_version',
           PN532Minimal._frame(0x02, b'') == bytes.fromhex('0000FF02FED4022A00'))

# --- Minimal init ---
sim = PN532Sim()
m = PN532Minimal(sim)
m.init()
check_true('init_commands', [c for c, _ in sim.commands] == [0x02, 0x14, 0x32])
check_true('init_sam', sim.commands[1][1] == b'\x01\x14\x01')
check_true('init_retries', sim.commands[2][1] == b'\x05\xff\x01\x02')
check_true('init_first_write', sim.writes[0] == bytes.fromhex('0000FF02FED4022A00'))
check_true('init_nack_after_header', sim.writes[1] == NACK)
check_true('ack_read_7', sim.reads.count(7) == 3)
check_true('response_full_read_len', 1 + 6 + 7 in sim.reads)   # firmware: LEN 6

sim = PN532Sim()
sim.ic = 0x31
try:
    PN532Minimal(sim).init()
    check_true('init_bad_ic_raises', False)
except PN532Error:
    check_true('init_bad_ic_raises', True)

sim = PN532Sim()
sim.fail_next_writes(3)
PN532Minimal(sim).init()
check_true('wake_write_retry', sim.commands[0][0] == 0x02 and len(sim.commands) == 3)

sim = PN532Sim()
sim.no_ack = True
try:
    PN532Minimal(sim).init()
    check_true('no_ack_raises', False)
except PN532Error:
    check_true('no_ack_raises', True)
check_true('no_ack_three_attempts', len(sim.commands) == 3)

# --- read_uid ---
sim = PN532Sim()
m = PN532Minimal(sim)
m.init()
uid = m.read_uid()
check_true('read_uid_value', uid == b'\x04\x11\x22\x33\x44\x55\x66')
check_true('read_uid_list_params', sim.commands[3] == (0x4A, b'\x01\x00'))
check_true('read_uid_release', sim.commands[4] == (0x52, b'\x01'))
sim.card = False
check_true('read_uid_no_card', m.read_uid() is None)
sim.no_response = True
check_true('read_uid_timeout_none', m.read_uid(0.01) is None)
check_true('read_uid_timeout_abort', sim.writes[-1] == ACK)
sim.no_response = False

sim.error_frame = True
try:
    m.read_uid()
    check_true('error_frame_raises', False)
except PN532Error:
    check_true('error_frame_raises', True)
sim.error_frame = False
sim.bad_dcs = True
try:
    m.read_uid()
    check_true('bad_dcs_raises', False)
except PN532Error:
    check_true('bad_dcs_raises', True)
sim.bad_dcs = False

# --- Full ---
sim = PN532Sim()
f = PN532Full(sim)
f.init()
fw = f.firmware_version()
check_true('fw_ic', fw['ic'] == 0x32 and fw['version'] == 1 and fw['revision'] == 6)
check_true('fw_support', fw['support'] == 7 and fw['iso14443a'] and fw['iso14443b'] and fw['iso18092'])
gs = f.general_status()
check_true('general_status', gs['error'] == 0 and gs['field'] and gs['targets'] == 1 and gs['sam_status'] == 0x01)

sim.list_response = bytes([2, 1, 0x00, 0x04, 0x20, 4, 1, 2, 3, 4, 5, 0x75, 0x77, 0x81, 0x02,
                           2, 0x00, 0x44, 0x00, 7, 9, 9, 9, 9, 9, 9, 9])
t = f.list_passive_targets(max_targets=2)
check_true('list_two_targets', len(t) == 2)
check_true('list_target_a', t[0].tg == 1 and t[0].sens_res == 0x0004 and t[0].sel_res == 0x20 and t[0].uid == b'\x01\x02\x03\x04')
check_true('list_target_ats', t[0].ats == b'\x05\x75\x77\x81\x02')
check_true('list_target_b', t[1].tg == 2 and t[1].sens_res == 0x0044 and t[1].uid == b'\x09' * 7 and t[1].ats == b'')
check_true('list_params', sim.commands[-1] == (0x4A, b'\x02\x00'))
sim.list_response = bytes([1, 1, 20, 0x01]) + bytes(range(1, 9)) + bytes(range(0x10, 0x18)) + b'\x12\xfc'
t = f.list_passive_targets(f.BAUD_212_F, 1, b'\x00\xff\xff\x01\x00')
check_true('list_felica', len(t) == 1 and t[0].uid == bytes(range(1, 9)) and t[0].ats == bytes(range(0x10, 0x18)) + b'\x12\xfc')
check_true('list_felica_params', sim.commands[-1] == (0x4A, b'\x01\x01\x00\xff\xff\x01\x00'))
sim.list_response = bytes([1, 1, 0x50, 0xA1, 0xA2, 0xA3, 0xA4, 0, 0, 0, 0, 0x81, 0x81, 0x00, 1, 0x00])
t = f.list_passive_targets(f.BAUD_106_B, 1, b'\x00')
check_true('list_type_b', len(t) == 1 and t[0].uid == b'\xa1\xa2\xa3\xa4' and t[0].ats == b'\x00')
sim.list_response = b'\x00'
check_true('list_empty', f.list_passive_targets() == [])
sim.list_response = None

check_true('mifare_read_block', f.mifare_read_block(1, 4) == bytes(range(4, 20)))
check_true('mifare_read_cmd', sim.commands[-1] == (0x40, b'\x01\x30\x04'))
f.mifare_authenticate(1, 7, b'\xff' * 6, b'\x04\x11\x22\x33\x44\x55\x66')
check_true('mifare_auth_payload', sim.commands[-1] == (0x40, b'\x01\x60\x07' + b'\xff' * 6 + b'\x33\x44\x55\x66'))
f.mifare_authenticate(1, 7, b'\x01' * 6, b'\xde\xad\xbe\xef', key_b=True)
check_true('mifare_auth_key_b', sim.commands[-1][1][1] == 0x61)
f.mifare_write_block(1, 4, bytes(16))
check_true('mifare_write_cmd', sim.commands[-1] == (0x40, b'\x01\xa0\x04' + bytes(16)))
check_true('ntag_read_page', f.ntag_read_page(1, 4) == bytes(range(4, 20)))
f.ntag_write_page(1, 5, b'\x01\x02\x03\x04')
check_true('ntag_write_cmd', sim.commands[-1] == (0x40, b'\x01\xa2\x05\x01\x02\x03\x04'))
try:
    f.ntag_write_page(1, 5, b'\x01')
    check_true('ntag_write_len_check', False)
except ValueError:
    check_true('ntag_write_len_check', True)
check_true('communicate_thru', f.communicate_thru(b'\x01\x02\x03') == b'\x03\x02\x01')

sim.status = 0x14
try:
    f.data_exchange(1, b'\x30\x00')
    check_true('data_exchange_status_raises', False)
except PN532Error as e:
    check_true('data_exchange_status_raises', e.code == 0x14)
try:
    f.release(1)
    check_true('release_status_raises', False)
except PN532Error as e:
    check_true('release_status_raises', e.code == 0x14)
sim.status = 0
f.release()
check_true('release_all', sim.commands[-1] == (0x52, b'\x00'))
f.select(1)
check_true('select', sim.commands[-1] == (0x54, b'\x01'))
f.deselect(1)
check_true('deselect', sim.commands[-1] == (0x44, b'\x01'))

f.set_rf_field(False)
check_true('rf_field_off', sim.commands[-1] == (0x32, b'\x01\x00'))
f.set_rf_field(True, auto_rfca=True)
check_true('rf_field_on_rfca', sim.commands[-1] == (0x32, b'\x01\x03'))
f.set_max_retries(0xFF)
check_true('max_retries', sim.commands[-1] == (0x32, b'\x05\xff\x01\xff'))
f.write_register(0x6302, 0x5A)
check_true('write_register', sim.commands[-1] == (0x08, b'\x63\x02\x5a'))
check_true('read_register', f.read_register(0x6302) == 0x5A)
check_true('read_register_cmd', sim.commands[-1] == (0x06, b'\x63\x02'))
check_true('self_test_echo', f.self_test())
check_true('self_test_echo_cmd', sim.commands[-1] == (0x00, b'\x00PERIPH01'))
check_true('self_test_rom', f.self_test(0x01))
check_true('send_command', f.send_command(0x02) == b'\x32\x01\x06\x07')
f.power_down()
check_true('power_down', sim.commands[-1] == (0x16, b'\x20'))

f.start_command(0x02)
check_true('wait_ready_pending', f.wait_ready(0.01))
check_true('poll_interrupt_ready', f.poll_interrupt())
check_true('read_response', f.read_response(0x02) == b'\x32\x01\x06\x07')
check_true('poll_interrupt_idle', not f.poll_interrupt())
check_true('wait_ready_idle', not f.wait_ready(0.005))


class FakePin:
    FALLING = 2

    def __init__(self):
        self.handler = None

    def on_edge(self, handler, edge):
        self.handler = handler

    def off_edge(self, handler):
        if self.handler is handler:
            self.handler = None


pin = FakePin()
hits = []
f.on_interrupt(lambda: hits.append(1), int_pin=pin)
check_true('on_interrupt_registers', pin.handler is not None)
pin.handler(pin)
check_true('on_interrupt_callback', hits == [1])
f.off_interrupt()
check_true('off_interrupt', pin.handler is None)

print('===DONE: %d passed, %d failed===' % (passed, failed))
sys.exit(0 if failed == 0 else 1)
