import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.power.lm25066 import LM25066Minimal, LM25066Full

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


def near(a, b, tol=1e-3):
    return abs(a - b) < tol


class CmdMock(I2CConnectionMock):
    """One byte string per PMBus command; every write is logged as (cmd, data)."""

    def __init__(self):
        super().__init__()
        self.cmds = {}
        self.log = []

    def read_reg(self, reg, length):
        return bytes(self.cmds.get(reg, bytes(length)))[:length].ljust(length, b'\x00')

    def write_reg(self, reg, data):
        data = bytes(data)
        self.log.append((reg, data))
        if data:
            self.cmds[reg] = data


def word(conn, cmd, value):
    conn.cmds[cmd] = bytes([value & 0xFF, (value >> 8) & 0xFF])


def block(conn, cmd, words):
    d = bytes([12])
    for w in words:
        d += bytes([w & 0xFF, (w >> 8) & 0xFF])
    conn.cmds[cmd] = d


# --- Minimal: constructor makes no bus access
conn = CmdMock()
m = LM25066Minimal(conn, 5.0, False)
check_true('init_no_writes', conn.log == [])

# VIN: Y = 2000 -> (200000 + 1800) / 22070
word(conn, 0x88, 2000)
check_true('vin', near(m.voltage_in(), 201800 / 22070.0))
word(conn, 0x8B, 0xF000 | 1500)  # upper bits ignored
check_true('vout', near(m.voltage_out(), 151800 / 22070.0))
# datasheet point: RS = 5 mohm, CL = GND, Y = 648 -> 1.02 A
word(conn, 0xD1, 648)
check_true('current_cl_gnd', near(m.current(), 70000 / 68305.0))
word(conn, 0xD2, 1000)
check_true('power_cl_gnd', near(m.power(), 103300 / (736 * 5.0)))
word(conn, 0x8D, 0x0190)
check_true('temp_25', near(m.temperature(), 25.0))
word(conn, 0x8D, 0xFF00)  # -16 C (signed)
check_true('temp_negative', near(m.temperature(), -16.0))

mh = LM25066Minimal(conn, 5.0, True)
word(conn, 0xD1, 648)
check_true('current_cl_vdd', near(mh.current(), (64800 + 3100) / (6854 * 5.0)))
word(conn, 0xD2, 1000)
check_true('power_cl_vdd', near(mh.power(), (100000 + 1900) / (369 * 5.0)))

# --- Full
conn = CmdMock()
f = LM25066Full(conn, 1.0, False)
word(conn, 0xD0, 3546 - 3)
check_true('vaux', near(f.voltage_aux(), 1.0))

block(conn, 0xDA, [0x0460, 648, 1500, 2000, 1000, 0x0190])
b = f.read_block()
check_true('block_diag', b['diagnostic'] == 0x0460)
check_true('block_current', near(b['current'], (64800 + 5200) / 13661.0))
check_true('block_vout', near(b['voltage_out'], 151800 / 22070.0))
check_true('block_vin', near(b['voltage_in'], 201800 / 22070.0))
check_true('block_power', near(b['power'], 103300 / 736.0))
check_true('block_temp', near(b['temperature'], 25.0))
block(conn, 0xE2, [0, 0, 0, 0, 0, 0])
check_true('avg_block', near(f.read_average()['voltage_in'], 1800 / 22070.0))
block(conn, 0xE0, [0x0001, 0, 0, 0, 0, 0])
check_true('black_box', f.black_box()['diagnostic'] == 1)
conn.cmds[0xDA] = bytes([5]) + bytes(12)
try:
    f.read_block()
    check_true('block_count_checked', False)
except ValueError:
    check_true('block_count_checked', True)

word(conn, 0xDC, 2000)
check_true('vin_avg', near(f.voltage_in_avg(), 201800 / 22070.0))
word(conn, 0xD5, 1000)
check_true('power_peak', near(f.power_peak(), 103300 / 736.0))
conn.log = []
f.clear_power_peak()
check_true('clear_peak', conn.log == [(0xD6, b'')])

conn.log = []
f.set_averaging(6)
check_true('set_avg', conn.log == [(0xDB, bytes([6]))])
try:
    f.set_averaging(13)
    check_true('avg_range', False)
except ValueError:
    check_true('avg_range', True)
conn.cmds[0xDB] = bytes([6])
check_true('get_avg', f.get_averaging() == 6)

# limits
conn.log = []
f.set_vin_uv_warn(10.0)  # Y = (22070*10 - 1800)/100 = 2189.0
check_true('vin_uv_warn_write', conn.log == [(0x58, bytes([0x8D, 0x08]))])
f.set_vin_uv_warn(None)
check_true('vin_uv_disable', conn.log[-1] == (0x58, bytes([0, 0])))
f.set_vin_ov_warn(None)
check_true('vin_ov_disable', conn.log[-1] == (0x57, bytes([0xFF, 0x0F])))
f.set_ot_fault(150.0)
check_true('ot_fault_150', conn.log[-1] == (0x4F, bytes([0x60, 0x09])))
f.set_ot_warn(125.0)
check_true('ot_warn_125', conn.log[-1] == (0x51, bytes([0xD0, 0x07])))
f.set_iin_warn(1.02)
y = (13661 * 1.02 - 5200) / 100.0
check_true('iin_warn', conn.log[-1] == (0xD3, bytes([int(round(y)) & 0xFF, int(round(y)) >> 8])))
f.set_pin_warn(1e9)
check_true('clamp', conn.log[-1] == (0xD4, bytes([0xFE, 0x0F])))
word(conn, 0x57, 0x0FFF)
word(conn, 0x58, 0x0000)
word(conn, 0x43, 0x0000)
word(conn, 0x4F, 0x0960)
word(conn, 0x51, 0x07D0)
word(conn, 0xD3, 0x0FFF)
word(conn, 0xD4, 0x0FFF)
lim = f.get_limits()
check_true('limits_disabled', lim['vin_ov_warn'] is None and lim['vin_uv_warn'] is None and
           lim['iin_warn'] is None and lim['pin_warn'] is None and lim['vout_uv_warn'] is None)
check_true('limits_ot', near(lim['ot_fault'], 150.0) and near(lim['ot_warn'], 125.0))

# status
conn.cmds[0x78] = bytes([0x49])
word(conn, 0x79, 0x3849)
conn.cmds[0x7A] = bytes([0x20])
conn.cmds[0x7C] = bytes([0x10])
conn.cmds[0x7D] = bytes([0x80])
conn.cmds[0x7E] = bytes([0x02])
conn.cmds[0x80] = bytes([0x10])
word(conn, 0xE1, 0x0460)
check_true('status_byte', f.status_byte() == 0x49)
check_true('status_word', f.status_word() == 0x3849)
check_true('status_groups', (f.status_vout(), f.status_input(), f.status_temperature(),
                             f.status_cml(), f.status_mfr()) == (0x20, 0x10, 0x80, 0x02, 0x10))
check_true('diagnostic', f.diagnostic_word() == 0x0460 and f.poll_interrupt() == 0x0460)

# control
conn.log = []
f.clear_faults()
check_true('clear_faults', conn.log == [(0x03, b'')])
f.operation_on()
f.operation_off()
check_true('operation', conn.log[1:] == [(0x01, bytes([0x80])), (0x01, bytes([0x00]))])
conn.cmds[0x01] = bytes([0x80])
conn.cmds[0x78] = bytes([0x00])
check_true('is_on', f.is_on())
conn.cmds[0x78] = bytes([0x40])
check_true('is_off_status', not f.is_on())

# device setup
conn.log = []
f.configure_device(retry_code=7, current_limit_high=True, cb_ratio_high=False)
check_true('device_setup', conn.log == [(0xD9, bytes([0xE0 | 0x04 | 0x10 | 0x02]))])
word(conn, 0xD1, 648)
check_true('coeff_updated', near(f.current(), (64800 + 3100) / 6854.0))
try:
    f.configure_device(retry_code=8)
    check_true('retry_range', False)
except ValueError:
    check_true('retry_range', True)
conn.cmds[0xD9] = bytes([0x16])
check_true('get_device_setup', f.device_setup() == 0x16)

# gate mask
conn.log = []
f.set_gate_mask(0x04)
check_true('gate_mask', conn.log == [(0xD7, bytes([0x04]))])
try:
    f.set_gate_mask(0x01)
    check_true('gate_mask_invalid', False)
except ValueError:
    check_true('gate_mask_invalid', True)
check_true('get_gate_mask', f.get_gate_mask() == 0x04)

conn.cmds[0x19] = bytes([0xB0])
check_true('capability', f.capability() == 0xB0)
conn.cmds[0x99] = bytes([3]) + b'NSC'
conn.cmds[0x9A] = bytes([8]) + b'LM25066\x00'
conn.cmds[0x9B] = bytes([2]) + b'AA'
check_true('mfr_id', f.manufacturer_id() == 'NSC')
check_true('model', f.model() == 'LM25066')
check_true('revision', f.revision() == 'AA')

# alert mask
word(conn, 0xD8, 0x0820)
conn.log = []
f.enable_interrupt(LM25066Full.SOURCE_VIN_UV_FAULT)
check_true('enable_irq', conn.log[-1] == (0xD8, bytes([0x00, 0x08])))
f.disable_interrupt(LM25066Full.SOURCE_VIN_UV_WARN)
check_true('disable_irq', conn.log[-1] == (0xD8, bytes([0x00, 0x28])))

print('Results: %d passed, %d failed' % (passed, failed))
sys.exit(1 if failed else 0)
