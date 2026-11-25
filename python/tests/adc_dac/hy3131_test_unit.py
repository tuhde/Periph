import sys

from periph.connection.spi_mock import SPIConnectionMock
from periph.chips.adc_dac.hy3131 import HY3131Minimal, HY3131Full

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


class HY3131Mock(SPIConnectionMock):
    """Models the HY3131 framing: (addr<<1)|R/W command, one blank clock on reads,
    auto-increment. INTF always reads back ``intf_value`` once, then clears."""

    def __init__(self):
        super().__init__()
        self.mem = {}
        self.intf_value = 0x07
        self.log = []

    def write(self, data):
        data = bytes(data)
        self.log.append(data)
        addr = data[0] >> 1
        for i, v in enumerate(data[1:]):
            self.mem[addr + i] = v

    def write_read(self, data, n):
        data = bytes(data)
        addr = data[0] >> 1
        vals = []
        for i in range(n):
            a = addr + i
            if a == 0x1E:
                vals.append(self.intf_value)
                self.intf_value = 0
            else:
                vals.append(self.mem.get(a, 0))
        # one-bit shift: bit stream is X d7..d0 d7..d0 ...
        out = bytearray(n)
        for i in range(n):
            prev = vals[i - 1] if i > 0 else 0
            out[i] = ((prev & 1) << 7) | (vals[i] >> 1) if i > 0 else vals[0] >> 1
        return bytes(out)


# --- Framing: write/read round trip through the one-bit-gap protocol ---
conn = HY3131Mock()
adc = HY3131Minimal(conn)
check_true('init_reset_first', conn.log[0] == bytes([0x37 << 1, 0x60]))
check_true('init_osc', conn.mem[0x33] == 0x28)
check_true('init_reference', conn.mem[0x31] == 0xC0 and conn.mem[0x30] == 0x00)
check_true('init_enable_ad1_last', conn.log[-1] == bytes([0x23 << 1, 0x80]) or conn.mem[0x23] == 0x80)
check_true('init_mux', conn.mem[0x24] == 0xA3 and conn.mem[0x28] == 0x11)
conn.mem[0x70] = 0xA5
conn.mem[0x71] = 0x5A
check_true('read_register_shift', adc.read_register(0x70, 2) == bytes([0xA5, 0x5A]))
adc.write_register(0x60, bytes([1, 2, 3]))
check_true('write_register_autoincrement', (conn.mem[0x60], conn.mem[0x61], conn.mem[0x62]) == (1, 2, 3))

# --- read_raw / read_voltage ---
conn.mem[0x00], conn.mem[0x01], conn.mem[0x02] = 0x00, 0x00, 0x40   # +2^22 -> half scale
conn.intf_value = 0x04
check_true('read_raw', adc.read_raw() == 0x400000)
conn.intf_value = 0x04
# half scale, IG=0.9, RG=1, VR=1.2 -> 0.5*1.2/0.9
check_true('read_voltage', abs(adc.read_voltage() - 0.5 * 1.2 / 0.9) < 1e-9)
conn.mem[0x00], conn.mem[0x01], conn.mem[0x02] = 0x00, 0x00, 0x80   # negative full scale
conn.intf_value = 0x04
check_true('read_raw_negative', adc.read_raw() == -8388608)

# --- flags are latched, not lost, across data_ready / read_raw ---
conn.intf_value = 0x04
check_true('data_ready_true', adc.data_ready())
conn.mem[0x00], conn.mem[0x01], conn.mem[0x02] = 0x01, 0x00, 0x00
check_true('latched_flag_used', adc.read_raw() == 1)

# --- Full ---
conn2 = HY3131Mock()
full = HY3131Full(conn2)
full.configure_ad1(osr=4, chop=2, input_gain=3, ref_gain=1, offset=1, buffers=0x3)
check_true('configure_ad1_r22', conn2.mem[0x22] == (1 << 5) | (2 << 3) | 4)
check_true('configure_ad1_r23', conn2.mem[0x23] == 0x80 | 0x10 | 0x03)
check_true('configure_ad1_gain', (conn2.mem[0x25] >> 4) & 3 == 3)
conn2.mem[0x00], conn2.mem[0x01], conn2.mem[0x02] = 0x00, 0x00, 0x40
conn2.intf_value = 0x04
# code/2^23*RG - DCOS = 0.5*0.3333.. - 0.25 ; * 1.2/3.6
check_true('configure_ad1_voltage', abs(full.read_voltage() - (0.5 * 0.3333333333 - 0.25) * 1.2 / 3.6) < 1e-6)

full.set_ad1_input(0x8, 0x5, filter_r=1)
check_true('set_ad1_input', conn2.mem[0x24] == 0x85 and (conn2.mem[0x33] >> 2) & 3 == 1)
full.set_ad1_reference(2, 6)
check_true('set_ad1_reference', conn2.mem[0x28] == 0x26)

full.enable_ad2(osr=3, input_gain=2, source=1, use_ad3=True)
check_true('enable_ad2_r26', conn2.mem[0x26] == 0x83)
check_true('enable_ad2_sdo23', conn2.mem[0x34] & 0x04 and conn2.mem[0x34] & 0x80)
check_true('enable_ad2_gain', (conn2.mem[0x25] >> 6) & 3 == 2)
conn2.mem[0x03], conn2.mem[0x04], conn2.mem[0x05] = 0xFF, 0xFF, 0xFF
conn2.intf_value = 0x02
check_true('read_ad2_sign', full.read_ad2_raw() == -1)

full.enable_lpf(bw=3)
full.enable_rms()
check_true('lpf_rms_bits', conn2.mem[0x29] == 0xC0 | (3 << 3))
for i, v in enumerate((0x00, 0x90, 0x01, 0x00, 0x00)):   # 0x19000 = 102400 -> sqrt = 320
    conn2.mem[0x09 + i] = v
conn2.intf_value = 0x10
check_true('read_rms', abs(full.read_rms() - 320.0) < 1e-9)

full.enable_peak_hold(source=2)
check_true('peak_hold_bits', conn2.mem[0x29] & 0x07 == 0x06)
for i, v in enumerate((0x00, 0x00, 0xFE, 0x00, 0x00, 0x01)):
    conn2.mem[0x0E + i] = v
check_true('read_peak', full.read_peak() == (-131072, 65536))

full.start_counter(gate_preload=0x1234)
check_true('counter_preload', conn2.mem[0x1C] == 0x34 and conn2.mem[0x1D] == 0x12)
check_true('counter_start', conn2.mem[0x20] & 0x0A == 0x0A)
conn2.mem[0x14] = 0x01
conn2.mem[0x1B], conn2.mem[0x1C], conn2.mem[0x1D] = 1, 0, 0
conn2.intf_value = 0x01
check_true('read_counter', full.read_counter()[3] is True)

conn2.intf_value = 0x14
check_true('read_flags', full.read_flags() == 0x14)
full.enable_interrupts(0xFF)
check_true('enable_interrupts_masked', conn2.mem[0x1F] == 0x1F)

# temperature: codes 100 and 300 -> TCode = 100
vals = iter((100, 300))
full._wait_flag = lambda mask: True
full.read_raw = lambda: next(vals)
check_true('temperature_code', full.read_temperature_code() == 100)

print('passed', passed, 'failed', failed)
sys.exit(1 if failed else 0)
